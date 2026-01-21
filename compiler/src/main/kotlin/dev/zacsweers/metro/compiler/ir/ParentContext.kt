// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.ir.graph.BindingPropertyCollector
import dev.zacsweers.metro.compiler.ir.graph.GraphNode
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.typeWith

internal class ParentContext(private val metroContext: IrMetroContext) {

  /**
   * Token returned by [mark] during validation phase. Contains enough information to resolve the
   * actual property during generation phase, after parent's [BindingPropertyCollector] has
   * finalized property kinds.
   */
  data class Token(
    val contextKey: IrContextualTypeKey,
    /** The typekey of the parent graph that owns this binding. */
    val ownerGraphKey: IrTypeKey,
    /** The receiver parameter for the parent graph (for generating access expressions). */
    val receiverParameter: IrValueParameter,
  )

  /**
   * Data for property access tracking (used during generation phase).
   *
   * Encapsulates all the information needed to generate a property access expression, including
   * ancestor graph chains (for extension graphs) and shard navigation. This provides a unified
   * abstraction for property access that handles:
   * - Direct property access: `receiver.property`
   * - Sharded property access: `receiver.shardProperty.property`
   * - Ancestor graph access: `receiver.parentGraph.grandparentGraph.property`
   * - Combined: `receiver.parentGraph.shardProperty.property`
   *
   * @property ownerGraphKey The type key of the graph that owns this property
   * @property property The property to access (may be on the graph directly or on a shard)
   * @property shardProperty If non-null, the property must be accessed through this shard property
   *   first (i.e., `receiver.shardProperty.property` instead of `receiver.property`)
   * @property ancestorChain If non-null, properties to chain through to reach the ancestor graph
   *   that owns this binding. For extension graphs accessing parent bindings.
   * @property shardGraphProperty For shard contexts, the property on the shard that references the
   *   main graph class. Must be accessed first before the ancestor chain.
   * @property isProviderProperty Whether the property returns a Provider type
   */
  @Poko
  class PropertyAccess(
    val ownerGraphKey: IrTypeKey,
    private val property: IrProperty,
    private val shardProperty: IrProperty? = null,
    private val ancestorChain: List<IrProperty>? = null,
    private val shardGraphProperty: IrProperty? = null,
    // TODO use AccessType
    val isProviderProperty: Boolean,
  ) {

    private val ancestorPropertiesChain by memoize {
      // Build the full chain of properties to traverse
      buildList {
        shardGraphProperty?.let(::add)
        ancestorChain?.let(::addAll)
        shardProperty?.let(::add)
        add(property)
      }
    }

    /**
     * Generates an IR expression to access this property on the receiver.
     *
     * Handles the full property access chain:
     * 1. If in shard context, first access the graph property: `receiver.graphProperty`
     * 2. Chain through ancestor graphs if present: `...parentGraph.grandparentGraph`
     * 3. Chain through shard property if present: `...shardProperty`
     * 4. Finally access the target property: `...property`
     *
     * @param baseReceiver The base receiver expression (typically `irGet(thisReceiver)`)
     */
    context(scope: IrBuilderWithScope)
    fun accessProperty(baseReceiver: IrExpression): IrExpression {
      // Fold through the chain to build the access expression
      return ancestorPropertiesChain.fold(baseReceiver) { receiver, prop ->
        scope.irGetProperty(receiver, prop)
      }
    }
  }

  private data class Level(
    val node: GraphNode,
    val deltaProvided: MutableSet<IrTypeKey> = mutableSetOf(),
    /** Tracks which contextual keys were used (preserving instance vs provider distinction) */
    val usedContextKeys: MutableSet<IrContextualTypeKey> = mutableSetOf(),
    /**
     * Context keys that were requested from this level via mark() but ended up being introduced to
     * a parent level (because the scope matched a parent). These need to be reported when this
     * level pops so the parent graph knows to generate Provider properties for them.
     */
    val parentLevelRequests: MutableSet<IrContextualTypeKey> = mutableSetOf(),
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
   * Marks a key as used and returns a token for later property resolution.
   *
   * During the validation phase, this method tracks which keys are used from parent graphs without
   * creating actual properties. Properties are created during the generation phase by the parent's
   * [BindingPropertyCollector], which determines the correct property kinds (FIELD vs GETTER).
   *
   * @param key The typekey to mark
   * @param scope Optional scope annotation for scoped bindings. When provided, the key is
   *   introduced at the appropriate parent level if not already present.
   * @param requiresProviderProperty If true, marks this as Provider<T> access. If false, marks as
   *   instance access. This info is captured in the returned token's contextKey.
   */
  fun mark(
    key: IrTypeKey,
    scope: IrAnnotation? = null,
    requiresProviderProperty: Boolean = scope != null,
  ): Token? {
    // Create the contextual key based on what kind of access is needed
    // Always must be a provider if scope is not null
    val contextKey = createContextKey(key, isProvider = requiresProviderProperty || scope != null)

    // Prefer the nearest provider (deepest level that introduced this key)
    keyIntroStack[key]?.lastOrNull()?.let { providerIdx ->
      val providerLevel = levels[providerIdx]

      // Track this context key as used at the matched provider level
      providerLevel.usedContextKeys.add(contextKey)

      return Token(
        contextKey = contextKey,
        ownerGraphKey = providerLevel.node.typeKey,
        receiverParameter = providerLevel.node.metroGraphOrFail.thisReceiverOrFail,
      )
    }

    // Not found but is scoped. Treat as constructor-injected with matching scope.
    if (scope != null) {
      val currentLevelIdx = levels.lastIndex
      for (i in levels.lastIndex downTo 0) {
        val level = levels[i]
        if (scope in level.node.scopes) {
          introduceAtLevel(i, key)

          // Track this context key as used at the level that owns the scope
          level.usedContextKeys.add(contextKey)

          // If this key was introduced to a parent level (not the current level),
          // track it so it gets reported when this level pops
          if (i < currentLevelIdx) {
            levels[currentLevelIdx].parentLevelRequests.add(contextKey)
          }

          return Token(
            contextKey = contextKey,
            ownerGraphKey = level.node.typeKey,
            receiverParameter = level.node.metroGraphOrFail.thisReceiverOrFail,
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

  fun pushParentGraph(node: GraphNode) {
    val idx = levels.size
    val level = Level(node)
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

    // Return both...
    // 1. Context keys used from this level
    // 2. Context keys that this level requested but were introduced to parent levels
    //    (these have the correct isWrappedInProvider info for the parent to use)
    return removed.usedContextKeys + removed.parentLevelRequests
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
      keyIntroStack.getOrPut(key, ::ArrayDeque).addLast(levelIdx)
    }
  }
}
