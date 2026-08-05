// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.calculateInitialCapacity

/**
 * Reusable cycle checker that avoids rebuilding adjacency maps for each candidate test. Instead, it
 * masks deferrable edges dynamically during iterative DFS traversal.
 */
internal class ReusableCycleChecker<V>(
  private val vertices: List<V>,
  private val sccAdjacency: Map<V, Set<V>>,
  private val deferrableEdgesFrom: Map<V, Set<V>>,
) {
  // Reuse these traversal structures across checks to reduce allocations.
  private val visited: HashSet<V>
  private val inStack: HashSet<V>
  // Keep DFS call frames on the heap so large dependency cycles don't overflow the stack.
  private val frames: ArrayDeque<Frame<V>>

  init {
    // Sized to the full SCC since the worst case is "every vertex visited."
    val cap = calculateInitialCapacity(vertices.size)
    visited = HashSet(cap)
    inStack = HashSet(cap)
    frames = ArrayDeque(vertices.size)
  }

  /**
   * Checks if the graph would be acyclic if we defer the given nodes. When a node is deferred, its
   * deferrable outgoing edges are skipped.
   */
  fun isAcyclicWith(deferredNodes: Set<V>): Boolean {
    visited.clear()
    inStack.clear()
    // A previous check may have returned early with unfinished frames still on the stack.
    frames.clear()

    for (node in vertices) {
      if (node !in visited && !isAcyclicFrom(node, deferredNodes)) {
        return false
      }
    }
    return true
  }

  /**
   * Checks for cycles reachable from [node] using depth-first traversal with heap-backed frames.
   */
  private fun isAcyclicFrom(node: V, deferredNodes: Set<V>): Boolean {
    pushFrame(node, deferredNodes)

    while (frames.isNotEmpty()) {
      val frame = frames.last()
      if (!frame.neighbors.hasNext()) {
        frames.removeLast()
        inStack.remove(frame.node)
        continue
      }

      val neighbor = frame.neighbors.next()
      // Skip deferrable edges from deferred nodes (this matches what sortVerticesInSCC will do)
      if (frame.deferrableFromThis != null && neighbor in frame.deferrableFromThis) continue
      if (neighbor in inStack) {
        // Cycle found
        return false
      }
      if (neighbor !in visited) {
        pushFrame(neighbor, deferredNodes)
      }
    }

    return true
  }

  private fun pushFrame(node: V, deferredNodes: Set<V>) {
    visited.add(node)
    inStack.add(node)

    val deferrableFromThis =
      if (node in deferredNodes) {
        deferrableEdgesFrom[node]
      } else {
        null
      }

    frames.addLast(Frame(node, sccAdjacency[node].orEmpty().iterator(), deferrableFromThis))
  }

  /** Saves the node, remaining neighbors, and deferred-edge mask for one DFS step. */
  private class Frame<V>(
    val node: V,
    val neighbors: Iterator<V>,
    val deferrableFromThis: Set<V>?,
  )
}
