// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0

package dev.zacsweers.metro.compiler.graph

import java.util.SortedMap
import java.util.SortedSet
import java.util.PriorityQueue

/**
 * Utility object for computing graph shards from a dependency graph.
 *
 * Metro generates a single implementation class for each dependency graph by default.  In very
 * large graphs this can exceed the JVM's limits on the size of a single class or method.  To
 * address this, graphs can be partitioned into multiple "shards".  Each shard represents a
 * contiguous subset of the dependency graph's strongly‑connected components in topological order.
 * Sharding preserves dependency ordering while ensuring that no shard contains more than a
 * configurable number of bindings.  This implementation is inspired by Dagger's component
 * sharding strategy.
 */
internal object GraphSharding {

  /**
   * The default maximum number of bindings per shard.  This value was chosen conservatively to
   * avoid hitting JVM method or class size limits.  It can be tuned or exposed via compiler
   * options in the future.
   */
  const val DEFAULT_BINDINGS_PER_SHARD: Int = 100

  /**
   * Compute a set of shards for the given adjacency map.  The adjacency map must be fully
   * populated: every vertex in the graph must be present as a key, and the values should list
   * direct dependencies.  Edges whose target vertex is absent from the map are ignored.
   *
   * The returned list of shards contains a list of vertices for each shard.  Vertices within a
   * shard may depend on each other or on vertices in earlier shards, but no vertex in a shard
   * depends on a vertex in a later shard.  Cyclic dependencies are kept together in the same
   * shard.
   *
   * @param adjacency a mapping from vertices to the set of vertices they depend upon
   * @param threshold the maximum number of vertices allowed in a single shard
   */
  internal fun <V : Any> computeShards(
    adjacency: SortedMap<V, out SortedSet<V>>, threshold: Int = DEFAULT_BINDINGS_PER_SHARD
  ): List<List<V>> {
    if (adjacency.isEmpty()) return emptyList()

    // Compute strongly connected components (SCCs) of the graph.  SCCs are represented as
    // Component<V> instances, each with a unique id and a list of vertices.
    val (components, componentOf) = adjacency.computeStronglyConnectedComponents()

    // Build a DAG of SCCs.  The DAG uses reversed edges: if vertex A depends on B, then B's
    // component points to A's component.  This reversal allows for a deterministic topological
    // ordering using Kahn's algorithm where dependencies appear before their dependents.
    val componentCount = components.size
    val dag: MutableMap<Int, MutableSet<Int>> = mutableMapOf()
    for ((fromVertex, outs) in adjacency) {
      val prereqComp = componentOf.getValue(fromVertex)
      for (toVertex in outs) {
        val dependentComp = componentOf.getValue(toVertex)
        if (prereqComp != dependentComp) {
          dag.getOrPut(dependentComp) { mutableSetOf() }.add(prereqComp)
        }
      }
    }

    // Compute the in‑degree of each component.  Components with an in‑degree of zero have no
    // dependencies and can be processed first.
    val inDegree = IntArray(componentCount)
    dag.values.flatten().forEach { comp -> inDegree[comp]++ }

    // Use a priority queue to ensure deterministic ordering when multiple components are ready.
    val ready: PriorityQueue<Int> = PriorityQueue()
    for (i in 0 until componentCount) {
      if (inDegree[i] == 0) ready.add(i)
    }

    // Kahn's algorithm for topologically sorting the component DAG.  The resulting list contains
    // component IDs in dependency order (dependencies first).
    val sortedComponentIds = mutableListOf<Int>()
    while (ready.isNotEmpty()) {
      val comp = ready.remove()
      sortedComponentIds.add(comp)
      val prereqs = dag[comp] ?: continue
      for (prereq in prereqs) {
        inDegree[prereq]--
        if (inDegree[prereq] == 0) ready.add(prereq)
      }
    }
    check(sortedComponentIds.size == componentCount) {
      "Cycle remained in component DAG while computing shards"
    }

    // Build a lookup for components by id for efficient access.
    val componentById = components.associateBy { it.id }

    // Group components into shards.  Each shard contains as many components as will fit within the
    // threshold.  Components are not split: a component's vertices always appear together.
    val shards = mutableListOf<MutableList<V>>()
    var currentShard = mutableListOf<V>()
    for (compId in sortedComponentIds) {
      val vertices = componentById.getValue(compId).vertices
      if (currentShard.isNotEmpty() && currentShard.size + vertices.size > threshold) {
        shards.add(currentShard)
        currentShard = mutableListOf()
      }
      currentShard.addAll(vertices)
    }
    if (currentShard.isNotEmpty()) {
      shards.add(currentShard)
    }
    return shards
  }

  /**
   * Convenience overload for computing shards directly from a bindings map.  The [bindings] map
   * should contain entries for every binding in the graph.  The [dependenciesOf] function
   * computes the keys that a binding depends on.  Dependencies that are not present in the
   * [bindings] map are ignored.
   *
   * @param bindings the mapping of keys to their binding definitions
   * @param dependenciesOf a function computing a binding's dependencies
   * @param threshold the maximum number of bindings allowed per shard
   */
  internal fun <K : Any, B : Any> computeShardsFromBindings(
    bindings: Map<K, B>,
    dependenciesOf: (B) -> Iterable<K>,
    threshold: Int = DEFAULT_BINDINGS_PER_SHARD
  ): List<List<K>> {
    // Build a sorted adjacency map of keys to their dependencies.  We use a TreeSet/TreeMap so
    // Tarjan's SCC algorithm runs deterministically.
    val adjacency: SortedMap<K, java.util.SortedSet<K>> = java.util.TreeMap<K, java.util.SortedSet<K>>()
    for ((key, binding) in bindings) {
      val deps = adjacency.getOrPut(key) { java.util.TreeSet<K>() }
      for (depKey in dependenciesOf(binding)) {
        if (bindings.containsKey(depKey)) {
          deps.add(depKey)
        }
      }
    }
    @Suppress("UNCHECKED_CAST")
    return computeShards(adjacency as SortedMap<K, SortedSet<K>>, threshold)
  }
}
