// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import dev.zacsweers.metro.compiler.calculateInitialCapacity

/**
 * Reusable cycle checker that avoids rebuilding adjacency maps for each candidate test.
 *
 * Initial checks skip deferred edges as they visit neighbors. Restoration checks enable cached rows
 * containing only eager neighbors. Later checks reuse these rows. Adjacency and edge masks must
 * remain unchanged for this checker's lifetime. The set of deferred vertices can change between
 * checks.
 */
internal class ReusableCycleChecker<V>(
  private val vertices: List<V>,
  private val sccAdjacency: Map<V, Set<V>>,
  private val deferrableEdgesFrom: Map<V, Set<V>>,
  private val ensureActive: () -> Unit = {},
) {
  // Reuse these traversal structures across checks to reduce allocations.
  private val visited: HashSet<V>
  private val inStack: HashSet<V>
  // Keep DFS call frames on the heap so large dependency cycles don't overflow the stack.
  private val frames: ArrayDeque<Frame<V>>
  // A failed traversal records the back edge's target, which may itself be null.
  private var cycleStart: V? = null
  // The first restoration check enables caching for later checks.
  private var hardAdjacency: MutableMap<V, List<V>>? = null

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
    resetTraversal()

    for (node in vertices) {
      ensureActive()
      if (node !in visited && !isAcyclicFrom(node, deferredNodes)) {
        return false
      }
    }
    return true
  }

  /**
   * Returns one cycle in traversal order. Each vertex appears once.
   *
   * The failed traversal leaves its active path in [frames]. Only this method copies the cycle into
   * a separate list. The returned list survives later checks on this instance.
   */
  fun findCycleWith(deferredNodes: Set<V>): List<V>? {
    if (isAcyclicWith(deferredNodes)) {
      return null
    }

    val cycle = ArrayList<V>()
    var inCycle = false
    for (frame in frames) {
      ensureActive()
      if (frame.node == cycleStart) {
        inCycle = true
      }
      if (inCycle) {
        cycle.add(frame.node)
      }
    }
    return cycle
  }

  /**
   * Checks whether restoring [node]'s deferred outgoing edges introduces a cycle.
   *
   * The graph must already have been acyclic while [node] was deferred, and [node] must no longer
   * be in [deferredNodes]. Any new cycle must pass through [node], so only vertices reachable from
   * it need to be checked.
   *
   * Enables cached rows for this check and subsequent checks.
   */
  fun isAcyclicAfterRestoringEdges(node: V, deferredNodes: Set<V>): Boolean {
    if (hardAdjacency == null) {
      hardAdjacency = HashMap()
    }
    resetTraversal()
    return isAcyclicFrom(node, deferredNodes)
  }

  private fun resetTraversal() {
    visited.clear()
    inStack.clear()
    // A previous check may have returned early with unfinished frames still on the stack.
    frames.clear()
    cycleStart = null
  }

  /**
   * Checks for cycles reachable from [node] using depth-first traversal with heap-backed frames.
   */
  private fun isAcyclicFrom(node: V, deferredNodes: Set<V>): Boolean {
    pushFrame(node, deferredNodes)

    while (frames.isNotEmpty()) {
      ensureActive()
      val frame = frames.last()
      if (!frame.neighbors.hasNext()) {
        frames.removeLast()
        inStack.remove(frame.node)
        continue
      }

      val neighbor = frame.neighbors.next()
      if (frame.deferrableFromThis != null && neighbor in frame.deferrableFromThis) {
        continue
      }
      if (neighbor in inStack) {
        cycleStart = neighbor
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

    val cache = hardAdjacency
    val frame =
      if (cache != null && !deferrableFromThis.isNullOrEmpty()) {
        Frame(node, cachedNeighborsFor(node, deferrableFromThis, cache), null)
      } else {
        Frame(node, sccAdjacency[node].orEmpty().iterator(), deferrableFromThis)
      }
    frames.addLast(frame)
  }

  /** Keeps the original neighbor order and caches a filtered row after it is complete. */
  private fun cachedNeighborsFor(
    node: V,
    deferrableFromThis: Set<V>,
    cache: MutableMap<V, List<V>>,
  ): Iterator<V> {
    val cached = cache[node]
    if (cached != null) {
      return cached.iterator()
    }

    val eagerNeighbors = ArrayList<V>()
    for (neighbor in sccAdjacency[node].orEmpty()) {
      ensureActive()
      // Match sortVerticesInSCC by omitting deferrable edges from deferred sources.
      if (neighbor !in deferrableFromThis) {
        eagerNeighbors.add(neighbor)
      }
    }
    val neighbors =
      if (eagerNeighbors.isEmpty()) {
        emptyList()
      } else {
        eagerNeighbors
      }
    cache[node] = neighbors
    return neighbors.iterator()
  }

  /** Saves the node, remaining neighbors, and any mask applied during traversal. */
  private class Frame<V>(
    val node: V,
    val neighbors: Iterator<V>,
    val deferrableFromThis: Set<V>?,
  )
}
