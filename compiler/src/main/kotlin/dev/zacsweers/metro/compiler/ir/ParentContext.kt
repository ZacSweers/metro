// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.ir.graph.BindingProperty
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.GraphPropertyData
import dev.zacsweers.metro.compiler.ir.graph.PropertyLocation
import dev.zacsweers.metro.compiler.ir.graph.PropertyType
import dev.zacsweers.metro.compiler.ir.graph.ensureInitialized
import dev.zacsweers.metro.compiler.ir.graph.graphPropertyData
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.suffixIfNot
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.typeWith

internal class ParentContext(private val metroContext: IrMetroContext) {

  // Data for property access tracking
  internal data class PropertyAccess(
    /** The parent graph impl class that owns the binding. */
    val parentGraphImpl: IrClass,
    val bindingProperty: BindingProperty,
    val receiverParameter: IrValueParameter,
  )

  private data class Level(
    val node: DependencyGraphNode,
    val propertyNameAllocator: NameAllocator,
    val deltaProvided: MutableSet<IrTypeKey> = mutableSetOf(),
    /** Tracks which contextual keys were used (preserving instance vs provider distinction) */
    val usedContextKeys: MutableSet<IrContextualTypeKey> = mutableSetOf(),
    /** Properties keyed by contextual type to support both instance and provider properties */
    val properties: MutableMap<IrContextualTypeKey, IrProperty> = mutableMapOf(),
  )

  // Stack of parent graphs (root at 0, top is last)
  private val levels = ArrayDeque<Level>()

  // Fast membership of “currently available anywhere in stack”, not including pending
  private val available = mutableSetOf<IrTypeKey>()

  // For each key, the stack of level indices where it was introduced (nearest provider = last)
  private val keyIntroStack = mutableMapOf<IrTypeKey, ArrayDeque<Int>>()

  // All active scopes (union of level.node.scopes)
  private val parentScopes = mutableSetOf<IrAnnotation>()

  // Keys collected before the next push
  private val pending = mutableSetOf<IrTypeKey>()

  fun add(key: IrTypeKey) {
    pending.add(key)
  }

  fun addAll(keys: Collection<IrTypeKey>) {
    if (keys.isNotEmpty()) pending.addAll(keys)
  }

  /**
   * Marks a key as used and returns property access information.
   *
   * @param key The type key to mark
   * @param scope Optional scope annotation for scoped bindings
   * @param requiresProviderProperty If true, creates/uses a Provider<T> property. If false, creates
   *   an instance property for direct access.
   */
  // TODO stick a cache in front of this
  fun mark(
    key: IrTypeKey,
    scope: IrAnnotation? = null,
    requiresProviderProperty: Boolean = true,
  ): PropertyAccess? {
    // Prefer the nearest provider (deepest level that introduced this key)
    keyIntroStack[key]?.lastOrNull()?.let { providerIdx ->
      val providerLevel = levels[providerIdx]

      // Create the contextual key based on what kind of property is needed
      val contextKey = createContextKey(key, requiresProviderProperty)

      // Get or create field in the provider level
      val property =
        providerLevel.properties.getOrPut(contextKey) {
          createPropertyInLevel(providerLevel, key, requiresProviderProperty)
        }

      // Only mark in the provider level - inner classes can access parent fields directly
      providerLevel.usedContextKeys.add(contextKey)
      val providerGraphImpl = providerLevel.node.metroGraphOrFail
      return PropertyAccess(
        providerGraphImpl,
        BindingProperty(property, PropertyLocation.InGraphImpl),
        providerGraphImpl.thisReceiverOrFail,
      )
    }

    // Not found but is scoped. Treat as constructor-injected with matching scope.
    if (scope != null) {
      for (i in levels.lastIndex downTo 0) {
        val level = levels[i]
        if (scope in level.node.scopes) {
          introduceAtLevel(i, key)

          // Create the contextual key based on what kind of property is needed
          val contextKey = createContextKey(key, requiresProviderProperty)

          // Get or create field
          val field =
            level.properties.getOrPut(contextKey) {
              createPropertyInLevel(level, key, requiresProviderProperty)
            }

          // Only mark in the level that owns the scope
          level.usedContextKeys.add(contextKey)
          val levelGraphImpl = level.node.metroGraphOrFail
          return PropertyAccess(
            levelGraphImpl,
            BindingProperty(field, PropertyLocation.InGraphImpl),
            levelGraphImpl.thisReceiverOrFail,
          )
        }
      }
    }
    // Else: no-op (unknown key without scope)
    return null
  }

  private fun createContextKey(key: IrTypeKey, isProvider: Boolean): IrContextualTypeKey {
    return if (isProvider) {
      val providerType = metroContext.metroSymbols.metroProvider.typeWith(key.type)
      IrContextualTypeKey.create(key, isWrappedInProvider = true, rawType = providerType)
    } else {
      IrContextualTypeKey.create(key)
    }
  }

  fun pushParentGraph(node: DependencyGraphNode, fieldNameAllocator: NameAllocator) {
    val idx = levels.size
    val level = Level(node, fieldNameAllocator)
    levels.addLast(level)
    parentScopes.addAll(node.scopes)

    if (pending.isNotEmpty()) {
      // Introduce each pending key *at this level only*
      for (k in pending) {
        introduceAtLevel(idx, k)
      }
      pending.clear()
    }
  }

  fun popParentGraph(): Set<IrContextualTypeKey> {
    check(levels.isNotEmpty()) { "No parent graph to pop" }
    val idx = levels.lastIndex
    val removed = levels.removeLast()

    // Remove scope union
    parentScopes.removeAll(removed.node.scopes)

    // Roll back introductions made at this level
    for (k in removed.deltaProvided) {
      val stack = keyIntroStack[k]!!
      check(stack.removeLast() == idx)
      if (stack.isEmpty()) {
        keyIntroStack.remove(k)
        available.remove(k)
      }
      // If non-empty, key remains available due to an earlier level
    }

    // Return the contextual keys that were used from this parent level
    return removed.usedContextKeys
  }

  val currentParentGraph: IrClass
    get() =
      levels.lastOrNull()?.node?.metroGraphOrFail
        ?: reportCompilerBug(
          "No parent graph on stack - this should only be accessed when processing extensions"
        )

  fun containsScope(scope: IrAnnotation): Boolean = scope in parentScopes

  operator fun contains(key: IrTypeKey): Boolean {
    return key in pending || key in available
  }

  fun availableKeys(): Set<IrTypeKey> {
    // Pending + all currently available
    if (pending.isEmpty()) return available.toSet()
    return buildSet(available.size + pending.size) {
      addAll(available)
      addAll(pending)
    }
  }

  fun usedContextKeys(): Set<IrContextualTypeKey> {
    return levels.lastOrNull()?.usedContextKeys ?: emptySet()
  }

  private fun introduceAtLevel(levelIdx: Int, key: IrTypeKey) {
    val level = levels[levelIdx]
    // If already introduced earlier, avoid duplicating per-level delta
    if (key !in level.deltaProvided) {
      level.deltaProvided.add(key)
      available.add(key)
      keyIntroStack.getOrPut(key) { ArrayDeque() }.addLast(levelIdx)
    }
  }

  private fun createPropertyInLevel(level: Level, key: IrTypeKey, isProvider: Boolean): IrProperty {
    val graphClass = level.node.metroGraphOrFail
    val propertyType =
      if (isProvider) {
        metroContext.metroSymbols.metroProvider.typeWith(key.type)
      } else {
        key.type
      }
    val contextKey = createContextKey(key, isProvider)
    val suffix = if (isProvider) "Provider" else "Instance"
    // Build but don't add, order will matter and be handled by the graph generator.
    // Create the property with a getter (no body) so child graphs can generate access code.
    // The backing field will be added by getOrCreateBindingProperty when the parent graph
    // is processed. The getter body is set later by generateReservedPropertyGetters.
    return graphClass.factory
      .buildProperty {
        name =
          level.propertyNameAllocator.newName(
            key.type.rawType().name.asString().decapitalizeUS().suffixIfNot(suffix).asName()
          )
        // Protected so child graphs (inner classes) can access
        visibility = DescriptorVisibilities.PROTECTED
      }
      .apply {
        parent = graphClass
        graphPropertyData = GraphPropertyData(contextKey, propertyType)
        // Add getter (no body) for child access. Body will be set after parent processing.
        // The backing field is added later by getOrCreateBindingProperty.
        with(metroContext) { ensureInitialized(PropertyType.FIELD_WITH_INLINE_GETTER) }
      }
  }

  // Get the property access for a contextual key if it exists
  fun getPropertyAccess(contextKey: IrContextualTypeKey): PropertyAccess? {
    keyIntroStack[contextKey.typeKey]?.lastOrNull()?.let { providerIdx ->
      val level = levels[providerIdx]
      level.properties[contextKey]?.let { property ->
        val graphImpl = level.node.metroGraphOrFail
        return PropertyAccess(
          graphImpl,
          BindingProperty(property, PropertyLocation.InGraphImpl),
          graphImpl.thisReceiverOrFail,
        )
      }
    }
    return null
  }
}
