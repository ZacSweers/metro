// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.ir.graph.DependencyGraphNode
import dev.zacsweers.metro.compiler.ir.graph.GraphPropertyData
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
    val parentKey: IrTypeKey,
    val property: IrProperty,
    val receiverParameter: IrValueParameter,
  )

  private data class Level(
    val node: DependencyGraphNode,
    val propertyNameAllocator: NameAllocator,
    val deltaProvided: MutableSet<IrTypeKey> = mutableSetOf(),
    val usedKeys: MutableSet<IrTypeKey> = mutableSetOf(),
    val properties: MutableMap<IrTypeKey, IrProperty> = mutableMapOf(),
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

  // TODO stick a cache in front of this
  fun mark(key: IrTypeKey, scope: IrAnnotation? = null): PropertyAccess? {
    // Prefer the nearest provider (deepest level that introduced this key)
    keyIntroStack[key]?.lastOrNull()?.let { providerIdx ->
      val providerLevel = levels[providerIdx]

      // Get or create field in the provider level
      val property =
        providerLevel.properties.getOrPut(key) { createPropertyInLevel(providerLevel, key) }

      // Only mark in the provider level - inner classes can access parent fields directly
      providerLevel.usedKeys.add(key)
      return PropertyAccess(
        providerLevel.node.typeKey,
        property,
        providerLevel.node.metroGraphOrFail.thisReceiverOrFail,
      )
    }

    // Not found but is scoped. Treat as constructor-injected with matching scope.
    if (scope != null) {
      for (i in levels.lastIndex downTo 0) {
        val level = levels[i]
        if (scope in level.node.scopes) {
          introduceAtLevel(i, key)

          // Get or create field
          val field = level.properties.getOrPut(key) { createPropertyInLevel(level, key) }

          // Only mark in the level that owns the scope
          level.usedKeys.add(key)
          return PropertyAccess(
            level.node.typeKey,
            field,
            level.node.metroGraphOrFail.thisReceiverOrFail,
          )
        }
      }
    }
    // Else: no-op (unknown key without scope)
    return null
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

  fun popParentGraph(): Set<IrTypeKey> {
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

    // Return the keys that were used from this parent level
    return removed.usedKeys.toSet()
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

  fun usedKeys(): Set<IrTypeKey> {
    return levels.lastOrNull()?.usedKeys ?: emptySet()
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

  private fun createPropertyInLevel(level: Level, key: IrTypeKey): IrProperty {
    val graphClass = level.node.metroGraphOrFail
    // Build but don't add, order will matter and be handled by the graph generator
    return graphClass.factory
      .buildProperty {
        name =
          level.propertyNameAllocator.newName(
            key.type.rawType().name.asString().decapitalizeUS().suffixIfNot("Provider").asName()
          )
        // TODO revisit? Can we skip synth accessors? Only if graph has extensions
        visibility = DescriptorVisibilities.PRIVATE
      }
      .apply {
        parent = graphClass
        graphPropertyData =
          GraphPropertyData(key, metroContext.metroSymbols.metroProvider.typeWith(key.type))

        // These must always be fields
        with(metroContext) { ensureInitialized(PropertyType.FIELD) }
      }
  }

  // Get the property access for a key if it exists
  fun getPropertyAccess(key: IrTypeKey): PropertyAccess? {
    keyIntroStack[key]?.lastOrNull()?.let { providerIdx ->
      val level = levels[providerIdx]
      level.properties[key]?.let { property ->
        return PropertyAccess(
          level.node.typeKey,
          property,
          level.node.metroGraphOrFail.thisReceiverOrFail,
        )
      }
    }
    return null
  }
}
