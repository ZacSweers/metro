// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

/**
 * An in-memory graph representation optimized for analysis algorithms.
 *
 * This provides efficient access to:
 * - Forward edges (dependencies): what does this binding depend on?
 * - Reverse edges (dependents): what bindings depend on this one?
 * - Binding metadata lookup by key
 */
public class BindingGraph
private constructor(
  private val bindings: Map<String, BindingMetadata>,
  private val forwardEdges: Map<String, Set<String>>,
  private val reverseEdges: Map<String, Set<String>>,
  public val graphName: String,
  public val scopes: List<String>,
) {
  /** All binding keys in this graph. */
  public val keys: Set<String>
    get() = bindings.keys

  /** Number of bindings in this graph. */
  public val size: Int
    get() = bindings.size

  /** Get binding metadata by key, or null if not found. */
  public fun getBinding(key: String): BindingMetadata? = bindings[key]

  /** Get all bindings in this graph. */
  public fun getAllBindings(): Collection<BindingMetadata> = bindings.values

  /**
   * Get the keys of bindings that this binding depends on (forward edges). Returns empty set if
   * binding not found or has no dependencies.
   */
  public fun getDependencies(key: String): Set<String> = forwardEdges[key] ?: emptySet()

  /**
   * Get the keys of bindings that depend on this binding (reverse edges). Returns empty set if
   * binding not found or has no dependents.
   */
  public fun getDependents(key: String): Set<String> = reverseEdges[key] ?: emptySet()

  /** Get fan-out (number of dependencies) for a binding. */
  public fun fanOut(key: String): Int = getDependencies(key).size

  /** Get fan-in (number of dependents) for a binding. */
  public fun fanIn(key: String): Int = getDependents(key).size

  /**
   * Find root bindings (entry points) - bindings with no dependents. These are typically graph
   * accessors or exposed bindings.
   */
  public fun findRoots(): Set<String> = keys.filter { fanIn(it) == 0 }.toSet()

  /**
   * Find leaf bindings - bindings with no dependencies. These are typically bound instances, object
   * classes, or external dependencies.
   */
  public fun findLeaves(): Set<String> = keys.filter { fanOut(it) == 0 }.toSet()

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
      val forwardEdges = mutableMapOf<String, MutableSet<String>>()
      val reverseEdges = mutableMapOf<String, MutableSet<String>>()

      // Initialize empty sets for all keys
      for (key in bindings.keys) {
        forwardEdges[key] = mutableSetOf()
        reverseEdges[key] = mutableSetOf()
      }

      // Build edges
      for (binding in metadata.bindings) {
        for (dep in binding.dependencies) {
          forwardEdges.getOrPut(binding.key) { mutableSetOf() }.add(dep.key)
          reverseEdges.getOrPut(dep.key) { mutableSetOf() }.add(binding.key)
        }
      }

      return BindingGraph(
        bindings = bindings,
        forwardEdges = forwardEdges,
        reverseEdges = reverseEdges,
        graphName = metadata.graph,
        scopes = metadata.scopes,
      )
    }
  }
}
