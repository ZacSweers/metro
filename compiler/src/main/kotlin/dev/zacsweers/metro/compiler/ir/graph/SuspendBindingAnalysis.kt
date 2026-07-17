// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.graph.BaseBinding
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Computes which bindings transitively require suspend initialization.
 *
 * The analysis is incremental because child graphs query their parent before the parent is sealed.
 * Discovery walks each binding's dependency edges once, recording reverse edges as it goes, and
 * suspendness propagates along those reverse edges from newly suspend bindings with a worklist.
 * Each resolved binding and edge is processed once across all queries rather than participating in
 * a fixpoint recomputation per query. Unresolved keys are retried once after the graph's generation
 * changes. When nothing reachable is suspend, propagation does no work at all.
 *
 * Multibinding dependencies are memoized snapshots of a mutable source-binding set. Accessing that
 * snapshot prevents later source bindings from being added, so this analysis cannot silently keep
 * stale multibinding edges.
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

/** Incrementally propagates suspend requirements along reverse dependency edges. */
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
  currentGraphGeneration: () -> Int = { 0 },
) {
  /** Bindings resolved so far. Misses are tracked in [unresolvedGenerations], never stored here. */
  private val discoveredBindings = mutableMapOf<TypeKey, Binding>()

  /** Keys whose dependency edges have been walked and recorded in [reverseEdges]. */
  private val expandedKeys = mutableSetOf<TypeKey>()

  /**
   * Keys that had no binding when last queried. Pre-seal, the parent graph is still being
   * populated, so these are retried after [currentGraphGeneration] changes. Remembering the
   * generation also avoids repeating the same failed lookup when another root reaches the key.
   */
  private val unresolvedGenerations = mutableMapOf<TypeKey, Int>()

  private val currentGraphGeneration = currentGraphGeneration
  private var analyzedGraphGeneration = currentGraphGeneration()

  /** Dependency key to consumers whose edge to that dependency propagates suspension. */
  private val reverseEdges = mutableMapOf<TypeKey, MutableList<TypeKey>>()

  /**
   * Edges whose dependency key was unresolved when walked. A `GraphDependency` that passes its
   * wrapper through stops propagation, and that check needs the resolved binding, so these edges
   * are classified when (if) the key resolves on a later retry.
   */
  private val pendingEdges =
    mutableMapOf<TypeKey, MutableList<PendingEdge<TypeKey, ContextualTypeKey>>>()

  private val suspendKeys = mutableSetOf<TypeKey>()
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
 * Whether this request defers initialization of its binding and therefore stops suspend
 * propagation. Validation separately rejects synchronous Provider or Lazy wrappers over a suspend
 * binding; that invalid edge must not also make its consumer transitively suspend.
 */
internal val IrContextualTypeKey.stopsSuspendPropagation: Boolean
  get() = isDeferrable

/**
 * Whether this request stops suspend propagation, considering both a deferrable wrapper and a graph
 * dependency that can pass its exact wrapper value through. [findBinding] resolves the request's
 * binding. Shared by graph validation and (via the analysis) child pre-seal queries.
 */
internal fun IrContextualTypeKey.stopsSuspendPropagation(
  findBinding: (IrTypeKey) -> IrBinding?
): Boolean {
  if (stopsSuspendPropagation) return true
  return (findBinding(typeKey) as? IrBinding.GraphDependency)?.canPassThrough(this) == true
}

/**
 * Whether this dependency edge makes its consumer transitively suspend. True when the requested key
 * is suspend ([isSuspendKey]) and the edge does not [stopsSuspendPropagation]. Shared by graph
 * validation and codegen so both agree on which edges block.
 */
internal fun IrContextualTypeKey.propagatesSuspend(
  isSuspendKey: (IrTypeKey) -> Boolean,
  findBinding: (IrTypeKey) -> IrBinding?,
): Boolean = isSuspendKey(typeKey) && !stopsSuspendPropagation(findBinding)
