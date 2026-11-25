// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import java.util.BitSet

/**
 * An int version of [ReusableCycleChecker] for one component.
 *
 * Vertices are local indices `0 until vertexCount`. Each vertex's edges live in [targets] from
 * `rowStarts[v]` until `rowStarts[v + 1]`. A check skips an edge when its source is deferred and
 * the edge is set in `deferrableEdges`. Checks start from vertices in index order and visit each
 * row in stored order. The inputs must stay unchanged for this checker's lifetime. The deferred set
 * can change between checks.
 */
internal class IntCycleChecker(
  private val vertexCount: Int,
  private val rowStarts: IntArray,
  private val targets: IntArray,
  deferrableEdges: BitSet,
  private val ensureActive: () -> Unit = {},
) {
  // Each vertex's edges without the deferrable ones, in stored order. A deferred vertex walks this
  // row, so repeated checks don't test every edge of a dense row again.
  private val eagerRowStarts = IntArray(vertexCount + 1)
  private val eagerTargets = IntArray(targets.size - deferrableEdges.cardinality())

  // Reuse these traversal structures across checks to reduce allocations.
  private val visited = BooleanArray(vertexCount)
  private val inStack = BooleanArray(vertexCount)
  // Keep DFS call frames on the heap so large dependency cycles don't overflow the stack.
  // Each vertex is pushed at most once per check, so the component size bounds the depth.
  private val frameVertices = IntArray(vertexCount)
  // Each frame's next edge in the row its vertex walks.
  private val frameCursors = IntArray(vertexCount)
  private var frameCount = 0
  // A failed traversal records the back edge's target.
  private var cycleStart = NO_VERTEX

  init {
    var next = 0
    for (vertex in 0 until vertexCount) {
      ensureActive()
      eagerRowStarts[vertex] = next
      for (edge in rowStarts[vertex] until rowStarts[vertex + 1]) {
        if (!deferrableEdges.get(edge)) {
          eagerTargets[next++] = targets[edge]
        }
      }
    }
    eagerRowStarts[vertexCount] = next
  }

  /**
   * Checks if the graph is acyclic when the deferrable edges of [deferred] vertices are skipped.
   */
  fun isAcyclicWith(deferred: BitSet): Boolean {
    resetTraversal()

    for (vertex in 0 until vertexCount) {
      ensureActive()
      if (!visited[vertex] && !isAcyclicFrom(vertex, deferred)) {
        return false
      }
    }
    return true
  }

  /**
   * Returns one cycle in traversal order, or null if there is none. Each vertex appears once.
   *
   * The failed traversal leaves its active path in the frames. The cycle is the part of that path
   * from the back edge's target to the top.
   */
  fun findCycleWith(deferred: BitSet): IntArray? {
    if (isAcyclicWith(deferred)) {
      return null
    }

    var first = 0
    while (frameVertices[first] != cycleStart) {
      ensureActive()
      first++
    }
    return frameVertices.copyOfRange(first, frameCount)
  }

  /**
   * Checks whether restoring [vertex]'s deferrable edges introduces a cycle.
   *
   * The graph must already have been acyclic while [vertex] was deferred, and [vertex] must no
   * longer be in [deferred]. Any new cycle must pass through [vertex], so only vertices reachable
   * from it need to be checked.
   */
  fun isAcyclicAfterRestoringEdges(vertex: Int, deferred: BitSet): Boolean {
    resetTraversal()
    return isAcyclicFrom(vertex, deferred)
  }

  private fun resetTraversal() {
    visited.fill(false)
    // A previous check may have returned early with unfinished frames still on the stack.
    inStack.fill(false)
    frameCount = 0
    cycleStart = NO_VERTEX
  }

  /** Checks for cycles reachable from [start] using depth-first traversal with heap frames. */
  private fun isAcyclicFrom(start: Int, deferred: BitSet): Boolean {
    pushFrame(start, deferred)

    while (frameCount > 0) {
      ensureActive()
      val top = frameCount - 1
      val vertex = frameVertices[top]
      // A deferred vertex skips its deferrable edges.
      val walksEagerRow = deferred.get(vertex)
      val edge = frameCursors[top]
      val end =
        if (walksEagerRow) {
          eagerRowStarts[vertex + 1]
        } else {
          rowStarts[vertex + 1]
        }
      if (edge == end) {
        frameCount--
        inStack[vertex] = false
        continue
      }

      frameCursors[top] = edge + 1
      val neighbor =
        if (walksEagerRow) {
          eagerTargets[edge]
        } else {
          targets[edge]
        }
      if (inStack[neighbor]) {
        cycleStart = neighbor
        return false
      }
      if (!visited[neighbor]) {
        pushFrame(neighbor, deferred)
      }
    }

    return true
  }

  private fun pushFrame(vertex: Int, deferred: BitSet) {
    visited[vertex] = true
    inStack[vertex] = true
    frameVertices[frameCount] = vertex
    frameCursors[frameCount] =
      if (deferred.get(vertex)) {
        eagerRowStarts[vertex]
      } else {
        rowStarts[vertex]
      }
    frameCount++
  }

  private companion object {
    const val NO_VERTEX = -1
  }
}
