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

  /** The root node key (the main dependency graph node). */
  public val graphRoot: String?
    get() = if (graphName in graph.nodes()) graphName else null

  /** Get binding metadata by key, or null if not found. */
  public fun getBinding(key: String): BindingMetadata? = bindings[key]

  /** Get all bindings in this graph. */
  public fun getAllBindings(): Collection<BindingMetadata> = bindings.values

  /** Get fan-out (number of dependencies) for a binding. */
  internal fun fanOut(key: String): Int = if (key in graph.nodes()) graph.outDegree(key) else 0

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

      // Build edges from binding dependencies
      for (binding in metadata.bindings) {
        val from = binding.key
        for (dep in binding.dependencies) {
          // Unwrap Provider<X>/Lazy<X> to X to match node IDs
          val to = unwrapTypeKey(dep.key)
          if (to in bindingKeys) {
            fullGraphBuilder.putEdge(from, to)
            // Skip deferrable (Provider/Lazy) edges from the eager graph -
            // they don't represent construction-time dependencies
            if (!dep.isDeferrable) {
              eagerGraphBuilder.putEdge(from, to)
            }
          }
        }
      }

      // Add accessor edges from the graph node to each accessor target
      // These are entry points, so only in the full graph (not eager)
      metadata.roots?.accessors?.forEach { accessor ->
        val targetKey = unwrapTypeKey(accessor.key)
        if (targetKey in bindingKeys) {
          fullGraphBuilder.putEdge(metadata.graph, targetKey)
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
