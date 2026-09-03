// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

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
 *
 * @param checkCanceled cancellation callback polled during analysis and witness construction. If it
 *   throws, discard this worklist because its incremental state may be incomplete.
 */
public class SuspendBindingWorklist<
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, TypeKey>,
  ContextualTypeKey : BaseContextualTypeKey<Type, TypeKey, ContextualTypeKey>,
  Binding : BaseBinding<Type, TypeKey, ContextualTypeKey>,
>(
  private val findBinding: (TypeKey) -> Binding?,
  private val bindingIsSuspend: (Binding) -> Boolean,
  private val skipDependencyTraversal: (Binding) -> Boolean,
  private val rules: SuspendBindingRules<Type, TypeKey, ContextualTypeKey, Binding>,
  private val currentGraphGeneration: () -> Int = { 0 },
  private val checkCanceled: () -> Unit = {},
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
  private var suspendKeys = mutableSetOf<TypeKey>()

  /** A later incremental update copies this set only when a result still shares its contents. */
  private var suspendKeysAreShared = false

  /** Newly marked keys whose consumers still need to be visited. */
  private val newlySuspend = ArrayDeque<TypeKey>()

  private class PendingEdge<TypeKey, ContextualTypeKey>(
    val consumer: TypeKey,
    val dependency: ContextualTypeKey,
  )

  public fun analyze(keys: Iterable<TypeKey>): Set<TypeKey> {
    val pending = ArrayDeque<TypeKey>()
    for (key in keys) {
      checkCanceled()
      if (key !in expandedKeys) {
        pending += key
      }
    }
    val graphGeneration = currentGraphGeneration()
    if (graphGeneration != analyzedGraphGeneration) {
      analyzedGraphGeneration = graphGeneration
      for (key in unresolvedGenerations.keys) {
        checkCanceled()
        pending += key
      }
    }
    if (pending.isNotEmpty()) {
      expand(pending)
      propagate()
    }
    return suspendKeys
  }

  /** Runs the analysis and creates witness paths only when a diagnostic asks for one. */
  public fun analyzeWithPaths(
    keys: Iterable<TypeKey>
  ): SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey> {
    analyze(keys)
    val snapshot =
      if (suspendKeys.isEmpty()) {
        emptySet()
      } else {
        suspendKeysAreShared = true
        suspendKeys
      }
    var witnessEdges: Map<TypeKey, SuspendBindingPathEdge<TypeKey, ContextualTypeKey>>? = null
    return SuspendBindingAnalysisResult(snapshot) { start, dependencyTypeKey ->
      if (start !in snapshot) {
        null
      } else {
        val startBinding = discoveredBindings[start]
        if (startBinding != null && bindingIsSuspend(startBinding)) {
          SuspendBindingPath(start, emptyList(), start, sourceIsSuspend = true)
        } else {
          var currentWitnessEdges = witnessEdges
          if (currentWitnessEdges == null) {
            currentWitnessEdges = buildWitnessEdges(snapshot, dependencyTypeKey)
            witnessEdges = currentWitnessEdges
          }
          pathFrom(start, currentWitnessEdges, dependencyTypeKey)
        }
      }
    }
  }

  public fun isSuspend(key: TypeKey): Boolean = key in analyze(listOf(key))

  private fun expand(pending: ArrayDeque<TypeKey>) {
    while (pending.isNotEmpty()) {
      checkCanceled()
      val key = pending.removeFirst()
      if (key in expandedKeys) continue
      val binding = resolve(key) ?: continue
      expandedKeys += key
      if (skipDependencyTraversal(binding)) continue

      for (dependency in binding.dependencies) {
        checkCanceled()
        if (dependency.isDeferrable) continue
        val depKey = dependency.typeKey
        val depBinding = resolve(depKey)
        if (depBinding == null) {
          pendingEdges.getOrPut(depKey, ::mutableListOf) += PendingEdge(key, dependency)
          continue
        }
        if (rules.canPassThrough(depBinding, dependency)) {
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
        checkCanceled()
        if (rules.canPassThrough(binding, edge.dependency)) {
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
    if (!suspendKeysAreShared) {
      if (suspendKeys.add(key)) {
        newlySuspend += key
      }
      return
    }

    if (key in suspendKeys) return
    suspendKeys = LinkedHashSet(suspendKeys)
    suspendKeysAreShared = false
    suspendKeys += key
    newlySuspend += key
  }

  private fun propagate() {
    while (newlySuspend.isNotEmpty()) {
      checkCanceled()
      val key = newlySuspend.removeFirst()
      for (consumer in reverseEdges[key].orEmpty()) {
        checkCanceled()
        markSuspend(consumer)
      }
    }
  }

  private fun buildWitnessEdges(
    snapshot: Set<TypeKey>,
    dependencyTypeKey: (ContextualTypeKey) -> TypeKey,
  ): Map<TypeKey, SuspendBindingPathEdge<TypeKey, ContextualTypeKey>> {
    val pending = ArrayDeque<TypeKey>()
    val distances = mutableMapOf<TypeKey, Int>()
    val witnessEdges = mutableMapOf<TypeKey, SuspendBindingPathEdge<TypeKey, ContextualTypeKey>>()

    for (key in snapshot) {
      checkCanceled()
      val binding = discoveredBindings[key] ?: continue
      if (bindingIsSuspend(binding)) {
        distances[key] = 0
        pending.addLast(key)
      }
    }

    // First find each consumer's shortest distance without letting source order break ties.
    while (pending.isNotEmpty()) {
      checkCanceled()
      val current = pending.removeFirst()
      val consumerDistance = distances.getValue(current) + 1
      for (consumerKey in reverseEdges[current].orEmpty()) {
        checkCanceled()
        if (consumerKey !in snapshot || consumerKey in distances) continue
        distances[consumerKey] = consumerDistance
        pending.addLast(consumerKey)
      }
    }

    // Among equally short paths, follow the dependency the consumer declared first.
    for ((consumerKey, distance) in distances) {
      checkCanceled()
      if (distance == 0) continue
      val consumer = discoveredBindings[consumerKey] ?: continue
      for (dependency in consumer.dependencies) {
        checkCanceled()
        val dependencyKey = dependencyTypeKey(dependency)
        if (distances[dependencyKey] != distance - 1) continue
        if (rules.stopsPropagation(dependency)) continue
        witnessEdges[consumerKey] = SuspendBindingPathEdge(consumerKey, dependency)
        break
      }
    }
    return witnessEdges
  }

  private fun pathFrom(
    start: TypeKey,
    witnessEdges: Map<TypeKey, SuspendBindingPathEdge<TypeKey, ContextualTypeKey>>,
    dependencyTypeKey: (ContextualTypeKey) -> TypeKey,
  ): SuspendBindingPath<TypeKey, ContextualTypeKey> {
    val path = mutableListOf<SuspendBindingPathEdge<TypeKey, ContextualTypeKey>>()
    var current = start
    while (true) {
      checkCanceled()
      val edge = witnessEdges[current] ?: break
      path += edge
      current = dependencyTypeKey(edge.dependency)
    }
    val source = discoveredBindings[current]
    val sourceIsSuspend = source != null && bindingIsSuspend(source)
    return SuspendBindingPath(start, path, current, sourceIsSuspend)
  }
}

/** A dependency edge on a path from a transitively suspend binding to its direct suspend source. */
public data class SuspendBindingPathEdge<TypeKey, ContextualTypeKey>(
  val consumerKey: TypeKey,
  val dependency: ContextualTypeKey,
)

/** Suspend analysis output, including one deterministic witness path for every suspend key. */
public class SuspendBindingAnalysisResult<TypeKey, ContextualTypeKey>
internal constructor(
  public val suspendKeys: Set<TypeKey>,
  private val findPath:
    (TypeKey, (ContextualTypeKey) -> TypeKey) -> SuspendBindingPath<TypeKey, ContextualTypeKey>?,
) {
  /**
   * Returns a witness path from [start] toward a directly suspend binding. Its edge list is empty
   * when [start] is itself directly suspend. Returns null only when [start] is not suspend. A path
   * whose walk could not reach a direct suspend source is returned partial with
   * [SuspendBindingPath.sourceIsSuspend] false.
   */
  public fun pathFrom(
    start: TypeKey,
    dependencyTypeKey: (ContextualTypeKey) -> TypeKey,
  ): SuspendBindingPath<TypeKey, ContextualTypeKey>? {
    return findPath(start, dependencyTypeKey)
  }
}

/**
 * A stable witness path from [startKey] toward [sourceKey]. When [sourceIsSuspend] is true the walk
 * reached a directly suspend binding. Otherwise the path is partial because a binding was missing
 * or no propagating dependency remained.
 */
public data class SuspendBindingPath<TypeKey, ContextualTypeKey>(
  val startKey: TypeKey,
  val edges: List<SuspendBindingPathEdge<TypeKey, ContextualTypeKey>>,
  val sourceKey: TypeKey,
  val sourceIsSuspend: Boolean,
)
