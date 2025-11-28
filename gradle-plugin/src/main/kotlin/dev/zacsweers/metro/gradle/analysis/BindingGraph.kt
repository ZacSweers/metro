// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import com.google.common.graph.Graph
import com.google.common.graph.GraphBuilder
import com.google.common.graph.ImmutableGraph

public class BindingGraph
private constructor(
  private val bindings: Map<String, BindingMetadata>,
  /** The full dependency graph with all edges. */
  public val graph: Graph<String>,
  /**
   * The "eager" dependency graph containing only non-deferrable edges. Useful for cycle detection
   * and critical path analysis where deferred dependencies (Provider, Lazy) don't contribute.
   */
  public val eagerGraph: Graph<String>,
  public val graphName: String,
  public val scopes: List<String>,
) {
  /** All binding keys in this graph. */
  public val keys: Set<String>
    get() = graph.nodes()

  /** Number of bindings in this graph. */
  public val size: Int
    get() = graph.nodes().size

  /** Get binding metadata by key, or null if not found. */
  public fun getBinding(key: String): BindingMetadata? = bindings[key]

  /** Get all bindings in this graph. */
  public fun getAllBindings(): Collection<BindingMetadata> = bindings.values

  /**
   * Get the keys of bindings that this binding depends on (forward edges). Returns empty set if
   * binding not found or has no dependencies.
   */
  public fun getDependencies(key: String): Set<String> =
    if (key in graph.nodes()) graph.successors(key) else emptySet()

  /**
   * Get the keys of bindings that depend on this binding (reverse edges). Returns empty set if
   * binding not found or has no dependents.
   */
  public fun getDependents(key: String): Set<String> =
    if (key in graph.nodes()) graph.predecessors(key) else emptySet()

  /** Get fan-out (number of dependencies) for a binding. */
  public fun fanOut(key: String): Int = if (key in graph.nodes()) graph.outDegree(key) else 0

  /** Get fan-in (number of dependents) for a binding. */
  public fun fanIn(key: String): Int = if (key in graph.nodes()) graph.inDegree(key) else 0

  /**
   * Find root bindings (entry points) - bindings with no dependents. These are typically graph
   * accessors or exposed bindings.
   */
  public fun findRoots(): Set<String> = graph.nodes().filter { graph.inDegree(it) == 0 }.toSet()

  /**
   * Find leaf bindings - bindings with no dependencies. These are typically bound instances, object
   * classes, or external dependencies.
   */
  public fun findLeaves(): Set<String> = graph.nodes().filter { graph.outDegree(it) == 0 }.toSet()

  /** Perform a breadth-first traversal from the given start key. Returns keys in BFS order. */
  public fun bfs(startKey: String, forward: Boolean = true): List<String> {
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    val result = mutableListOf<String>()

    queue.add(startKey)
    visited.add(startKey)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      result.add(current)

      val neighbors = if (forward) getDependencies(current) else getDependents(current)
      for (neighbor in neighbors) {
        if (neighbor !in visited && neighbor in keys) {
          visited.add(neighbor)
          queue.add(neighbor)
        }
      }
    }

    return result
  }

  /** Perform a depth-first traversal from the given start key. Returns keys in DFS order. */
  public fun dfs(startKey: String, forward: Boolean = true): List<String> {
    val visited = mutableSetOf<String>()
    val result = mutableListOf<String>()

    fun visit(key: String) {
      if (key in visited || key !in keys) return
      visited.add(key)
      result.add(key)
      val neighbors = if (forward) getDependencies(key) else getDependents(key)
      for (neighbor in neighbors) {
        visit(neighbor)
      }
    }

    visit(startKey)
    return result
  }

  public companion object {
    /** Build a [BindingGraph] from [GraphMetadata]. */
    public fun from(metadata: GraphMetadata): BindingGraph {
      val bindings = metadata.bindings.associateBy { it.key }
      val bindingKeys = bindings.keys

      // Build both full and eager graphs
      val fullGraphBuilder = GraphBuilder.directed().allowsSelfLoops(false).build<String>()
      val eagerGraphBuilder = GraphBuilder.directed().allowsSelfLoops(false).build<String>()

      // Add all nodes first
      for (key in bindingKeys) {
        fullGraphBuilder.addNode(key)
        eagerGraphBuilder.addNode(key)
      }

      // Build edges
      for (binding in metadata.bindings) {
        val from = binding.key
        for (dep in binding.dependencies) {
          val to = dep.key
          if (to in bindingKeys) {
            fullGraphBuilder.putEdge(from, to)
            if (!dep.isDeferrable) {
              eagerGraphBuilder.putEdge(from, to)
            }
          }
        }
      }

      return BindingGraph(
        bindings = bindings,
        graph = ImmutableGraph.copyOf(fullGraphBuilder),
        eagerGraph = ImmutableGraph.copyOf(eagerGraphBuilder),
        graphName = metadata.graph,
        scopes = metadata.scopes,
      )
    }
  }
}
