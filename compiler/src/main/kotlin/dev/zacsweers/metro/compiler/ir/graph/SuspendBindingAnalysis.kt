// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey

/**
 * Computes which bindings transitively require suspend evaluation.
 *
 * The analysis is incremental because child graphs query their parent before the parent is sealed.
 * Discovery walks each binding's dependency edges once, recording reverse edges as it goes, and
 * suspendness propagates along those reverse edges from newly suspend bindings with a worklist.
 * Each binding and edge is processed a constant number of times across all queries, so total work
 * is O(bindings + edges) rather than a fixpoint recomputation per query. When nothing reachable is
 * suspend, propagation does no work at all.
 *
 * Multibinding dependencies are memoized snapshots of a mutable source-binding set. That is safe
 * here because every `addSourceBinding` call happens inside `BindingGraphGenerator.generate()`,
 * which completes before the first pre-seal child query and before `seal()`. If contributors are
 * ever registered later than that, this analysis (and the memoize on
 * [IrBinding.Multibinding.dependencies]) must be revisited.
 */
internal class SuspendBindingAnalysis(private val findBinding: (IrTypeKey) -> IrBinding?) {
  /** Bindings resolved so far. Misses are tracked in [unresolvedKeys], never stored here. */
  private val discoveredBindings = mutableMapOf<IrTypeKey, IrBinding>()

  /** Keys whose dependency edges have been walked and recorded in [reverseEdges]. */
  private val expandedKeys = mutableSetOf<IrTypeKey>()

  /**
   * Keys that had no binding when last queried. Pre-seal, the parent graph is still being
   * populated, so these are retried whenever a query explores a new key (the only time the
   * underlying graph can have grown).
   */
  private val unresolvedKeys = mutableSetOf<IrTypeKey>()

  /** Dependency key to consumers whose edge to that dependency propagates suspension. */
  private val reverseEdges = mutableMapOf<IrTypeKey, MutableList<IrTypeKey>>()

  /**
   * Edges whose dependency key was unresolved when walked. A `GraphDependency` that passes its
   * wrapper through stops propagation, and that check needs the resolved binding, so these edges
   * are classified when (if) the key resolves on a later retry.
   */
  private val pendingEdges = mutableMapOf<IrTypeKey, MutableList<PendingEdge>>()

  private val suspendKeys = mutableSetOf<IrTypeKey>()
  private val newlySuspend = ArrayDeque<IrTypeKey>()

  private class PendingEdge(val consumer: IrTypeKey, val dependency: IrContextualTypeKey)

  fun analyze(keys: Iterable<IrTypeKey>): Set<IrTypeKey> {
    val pending = ArrayDeque<IrTypeKey>()
    for (key in keys) {
      if (key !in expandedKeys) {
        pending += key
      }
    }
    if (pending.isNotEmpty()) {
      pending += unresolvedKeys
      expand(pending)
      propagate()
    }
    return suspendKeys
  }

  fun isSuspend(key: IrTypeKey): Boolean = key in analyze(listOf(key))

  private fun expand(pending: ArrayDeque<IrTypeKey>) {
    // Keys that missed during this pass, so each is looked up at most once per analyze() call.
    val missedThisPass = mutableSetOf<IrTypeKey>()
    while (pending.isNotEmpty()) {
      val key = pending.removeFirst()
      if (key in expandedKeys) continue
      val binding = resolve(key, missedThisPass) ?: continue
      expandedKeys += key
      // Assisted factories are constructed on demand and never become transitively suspend; their
      // suspend requirements are validated separately against the target binding.
      if (binding is IrBinding.AssistedFactory) continue

      for (dependency in binding.dependencies) {
        if (dependency.stopsSuspendPropagation) continue
        val depKey = dependency.typeKey
        val depBinding = resolve(depKey, missedThisPass)
        if (depBinding == null) {
          pendingEdges.getOrPut(depKey, ::mutableListOf) += PendingEdge(key, dependency)
          continue
        }
        if (depBinding is IrBinding.GraphDependency && depBinding.canPassThrough(dependency)) {
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

  private fun resolve(key: IrTypeKey, missedThisPass: MutableSet<IrTypeKey>): IrBinding? {
    discoveredBindings[key]?.let {
      return it
    }
    if (key in missedThisPass) return null
    val binding = findBinding(key)
    if (binding == null) {
      missedThisPass += key
      unresolvedKeys += key
      return null
    }
    unresolvedKeys -= key
    discoveredBindings[key] = binding
    if (binding.isSuspend) {
      markSuspend(key)
    }
    // Classify edges that were waiting on this key's binding.
    pendingEdges.remove(key)?.let { edges ->
      for (edge in edges) {
        if (binding is IrBinding.GraphDependency && binding.canPassThrough(edge.dependency)) {
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

  private fun markSuspend(key: IrTypeKey) {
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
 * Whether this request defers evaluation of its binding and therefore stops suspend propagation.
 * Validation separately rejects synchronous Provider or Lazy wrappers over a suspend binding; that
 * invalid edge must not also make its consumer transitively suspend.
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
