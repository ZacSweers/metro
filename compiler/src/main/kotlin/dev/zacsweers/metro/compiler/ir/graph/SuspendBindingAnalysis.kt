// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.BaseBinding
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Metro's IR-specific entry point for finding bindings that require suspend initialization.
 *
 * [SuspendBindingWorklist] contains the graph algorithm. This adapter supplies the IR rules for
 * recognizing suspend bindings, skipping assisted-factory dependencies, and passing an exact graph
 * dependency wrapper through without making its consumer suspend. Keeping the worklist generic also
 * lets its behavior be tested with small fake bindings instead of compiler IR.
 *
 * Child graphs can query their parent before the parent graph is sealed. The same analysis instance
 * therefore accepts more bindings as the parent grows and updates its earlier answers. Final graph
 * validation uses these rules after the full binding set is available.
 *
 * Reading a multibinding's dependencies freezes its current set of contributions. The graph
 * prevents later contributions from being added, so the dependency edges cached here remain valid.
 */
internal class SuspendBindingAnalysis(
  findBinding: (IrTypeKey) -> IrBinding?,
  currentGraphGeneration: () -> Int = { 0 },
) {
  private val worklist =
    SuspendBindingWorklist(
      findBinding = findBinding,
      bindingIsSuspend = { it.isSuspend },
      skipDependencyTraversal = { it is IrBinding.AssistedFactory },
      canPassThrough = { binding, dependency ->
        binding is IrBinding.GraphDependency && binding.canPassThrough(dependency)
      },
      currentGraphGeneration = currentGraphGeneration,
    )

  fun analyze(keys: Iterable<IrTypeKey>): Set<IrTypeKey> = worklist.analyze(keys)

  fun isSuspend(key: IrTypeKey): Boolean = worklist.isSuspend(key)
}

/**
 * Finds every binding that must be initialized from a suspend context.
 *
 * A binding requires suspend initialization when it is itself suspend or has a non-deferred
 * dependency that requires suspend initialization. Propagation runs opposite to dependency lookup:
 * ```
 * Dependency lookup:    A --> B --> C (suspend)
 * Suspend propagation:  A <-- B <-- C
 *
 * Deferred dependency:  A --> suspend () -> C
 *                             propagation stops
 * ```
 *
 * The worklist is incremental because a child graph may inspect an unsealed parent:
 * ```
 * query A --> resolve A and B --> C is missing
 * graph changes --> retry C --> C is suspend --> mark C, B, then A
 * ```
 *
 * Each binding's dependencies are expanded once. A missing binding is retried only after the graph
 * generation changes, and each key enters the propagation queue only the first time it is marked
 * suspend. These properties also make cycles terminate without a separate fixpoint pass.
 */
internal class SuspendBindingWorklist<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
>(
  private val findBinding: (TypeKey) -> Binding?,
  private val bindingIsSuspend: (Binding) -> Boolean,
  private val skipDependencyTraversal: (Binding) -> Boolean,
  private val canPassThrough: (Binding, ContextualTypeKey) -> Boolean,
  private val currentGraphGeneration: () -> Int = { 0 },
) {
  /** Successfully resolved bindings. Missing bindings are tracked in [unresolvedGenerations]. */
  private val discoveredBindings = mutableMapOf<TypeKey, Binding>()

  /** Bindings whose dependencies have already been recorded. */
  private val expandedKeys = mutableSetOf<TypeKey>()

  /**
   * The graph generation of each failed lookup. A miss is retried after the graph changes, but not
   * every time another root reaches the same key in the meantime.
   */
  private val unresolvedGenerations = mutableMapOf<TypeKey, Int>()

  private var analyzedGraphGeneration = currentGraphGeneration()

  /**
   * Dependency-to-consumer edges along which suspend requirements propagate.
   *
   * The adjacency buckets are lists because they are append-only and usually small. Repeated
   * requests can add the same consumer more than once, but [markSuspend] makes those duplicates
   * harmless.
   */
  private val reverseEdges = mutableMapOf<TypeKey, MutableList<TypeKey>>()

  /**
   * Edges waiting for their dependency binding to resolve.
   *
   * Each edge retains its contextual dependency because [canPassThrough] may differ between wrapper
   * shapes for the same type key. Once the binding resolves, the edge either becomes a
   * [reverseEdges] entry or is discarded as pass-through.
   */
  private val pendingEdges =
    mutableMapOf<TypeKey, MutableList<PendingEdge<TypeKey, ContextualTypeKey>>>()

  /** Keys already known to require suspend initialization. */
  private val suspendKeys = mutableSetOf<TypeKey>()

  /** Newly marked keys whose consumers still need to be visited. */
  private val newlySuspend = ArrayDeque<TypeKey>()

  private class PendingEdge<TypeKey, ContextualTypeKey>(
    val consumer: TypeKey,
    val dependency: ContextualTypeKey,
  )

  fun analyze(keys: Iterable<TypeKey>): Set<TypeKey> {
    val pending = ArrayDeque<TypeKey>()
    for (key in keys) {
      if (key !in expandedKeys) {
        pending += key
      }
    }
    val graphGeneration = currentGraphGeneration()
    if (graphGeneration != analyzedGraphGeneration) {
      analyzedGraphGeneration = graphGeneration
      pending += unresolvedGenerations.keys
    }
    if (pending.isNotEmpty()) {
      expand(pending)
      propagate()
    }
    return suspendKeys
  }

  fun isSuspend(key: TypeKey): Boolean = key in analyze(listOf(key))

  private fun expand(pending: ArrayDeque<TypeKey>) {
    while (pending.isNotEmpty()) {
      val key = pending.removeFirst()
      if (key in expandedKeys) continue
      val binding = resolve(key) ?: continue
      expandedKeys += key
      if (skipDependencyTraversal(binding)) continue

      for (dependency in binding.dependencies) {
        if (dependency.isDeferrable) continue
        val depKey = dependency.typeKey
        val depBinding = resolve(depKey)
        if (depBinding == null) {
          pendingEdges.getOrPut(depKey, ::mutableListOf) += PendingEdge(key, dependency)
          continue
        }
        if (canPassThrough(depBinding, dependency)) {
          continue
        }
        reverseEdges.getOrPut(depKey, ::mutableListOf) += key
        if (depKey in suspendKeys) {
          markSuspend(key)
        }
        if (depKey !in expandedKeys) {
          pending += depKey
        }
      }
    }
  }

  private fun resolve(key: TypeKey): Binding? {
    discoveredBindings[key]?.let {
      return it
    }
    if (unresolvedGenerations[key] == analyzedGraphGeneration) return null
    val binding = findBinding(key)
    if (binding == null) {
      unresolvedGenerations[key] = analyzedGraphGeneration
      return null
    }
    unresolvedGenerations -= key
    discoveredBindings[key] = binding
    if (bindingIsSuspend(binding)) {
      markSuspend(key)
    }
    // Classify edges that were waiting on this key's binding.
    pendingEdges.remove(key)?.let { edges ->
      for (edge in edges) {
        if (canPassThrough(binding, edge.dependency)) {
          continue
        }
        reverseEdges.getOrPut(key, ::mutableListOf) += edge.consumer
        if (key in suspendKeys) {
          markSuspend(edge.consumer)
        }
      }
    }
    return binding
  }

  private fun markSuspend(key: TypeKey) {
    if (suspendKeys.add(key)) {
      newlySuspend += key
    }
  }

  private fun propagate() {
    while (newlySuspend.isNotEmpty()) {
      val key = newlySuspend.removeFirst()
      for (consumer in reverseEdges[key].orEmpty()) {
        markSuspend(consumer)
      }
    }
  }
}

/**
 * Whether this request defers initialization and therefore stops suspend propagation.
 *
 * Validation separately rejects synchronous `Provider` or `Lazy` wrappers over a suspend binding.
 * That invalid request must not also make its consumer transitively suspend.
 */
internal val IrContextualTypeKey.stopsSuspendPropagation: Boolean
  get() = isDeferrable

/**
 * Whether this request stops suspend propagation because it defers initialization or because a
 * graph dependency can pass the exact wrapper value through.
 *
 * Shared by graph validation and child queries against an unsealed parent graph.
 */
internal fun IrContextualTypeKey.stopsSuspendPropagation(
  findBinding: (IrTypeKey) -> IrBinding?
): Boolean {
  if (stopsSuspendPropagation) return true
  return (findBinding(typeKey) as? IrBinding.GraphDependency)?.canPassThrough(this) == true
}

/**
 * Whether this dependency makes its consumer suspend: the requested binding is suspend and this
 * request does not defer or pass it through.
 *
 * Shared by graph validation and code generation so both use the same propagation rules.
 */
internal fun IrContextualTypeKey.propagatesSuspend(
  isSuspendKey: (IrTypeKey) -> Boolean,
  findBinding: (IrTypeKey) -> IrBinding?,
): Boolean = isSuspendKey(typeKey) && !stopsSuspendPropagation(findBinding)
