/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import java.util.SortedSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TopologicalSortTest {
  @Test
  fun emptyEdges() {
    val unsorted = listOf("a", "b", "c")
    val sorted = listOf("a", "b", "c")
    val sourceToTarget = edges()
    val actual = unsorted.topologicalSort(sourceToTarget)
    assertTrue(sorted.isTopologicallySorted(sourceToTarget))
    assertEquals(sorted, actual)
  }

  @Test
  fun alreadySorted() {
    val sourceToTarget = edges("ba", "cb")
    val unsorted = listOf("a", "b", "c")
    val sorted = listOf("a", "b", "c")
    val actual = unsorted.topologicalSort(sourceToTarget)
    assertTrue(sorted.isTopologicallySorted(sourceToTarget))
    assertEquals(sorted, actual)
  }

  @Test
  fun happyPath() {
    assertTopologicalSort(unsorted = listOf("a", "b"), sorted = listOf("b", "a"), "ab")
    assertTopologicalSort(
      unsorted = listOf("b", "c", "d", "a"),
      sorted = listOf("a", "b", "c", "d"),
      "ba",
      "ca",
      "db",
      "dc",
    )
    assertTopologicalSort(
      unsorted = listOf("d", "b", "c", "a"),
      sorted = listOf("a", "b", "c", "d"),
      "ba",
      "ca",
      "db",
      "dc",
    )
    assertTopologicalSort(
      unsorted = listOf("a", "b", "c", "d", "e"),
      sorted = listOf("d", "c", "a", "e", "b"),
      "be",
      "bc",
      "ec",
      "ac",
      "cd",
      "ad",
    )
  }

  @Test
  fun cycleCrashes() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        listOf("a", "b").topologicalSort(edges("ab", "ba"))
      }
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo(
        """
      |No topological ordering is possible for these items:
      |  a (b)
      |  b (a)
      """
          .trimMargin()
      )
  }

  @Test
  fun elementConsumedButNotDeclaredCrashes() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        listOf("a", "b").topologicalSort(edges("ab", "ac"))
      }
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo(
        """
      |No element for c found for a
      """
          .trimMargin()
      )
  }

  @Test
  fun exceptionMessageOnlyIncludesProblematicItems() {
    val exception =
      assertFailsWith<IllegalArgumentException> {
        listOf("a", "b", "c", "d", "e")
          .topologicalSort(edges("ab", "bc", "da", "de", "db", "ed", "ef"))
      }
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo(
        """
      |No element for f found for e
      """
          .trimMargin()
      )
  }

  @Test
  fun deterministicWhenThereAreNoEdges() {
    val unsorted = listOf("c", "a", "b")
    val expected = listOf("a", "b", "c")

    val actual = unsorted.topologicalSort(edges())

    assertEquals(expected, actual)
    assertTrue(actual.isTopologicallySorted(edges()))
  }

  /**
   * ```
   * a
   * ├──▶ b
   * └──▶ c
   * ```
   *
   * After 'a' is processed both 'b' and 'c' are ready. Dequeue 'b' before 'c' because 'b' < 'c'
   * according to Comparable order.
   */
  @Test
  fun deterministicTieBreakWhenMultipleNodesReadyTogether() {
    val sourceToTarget = edges("ab", "ac")

    val unsorted = listOf("c", "a", "b")
    val expected = listOf("b", "c", "a")

    val actual = unsorted.topologicalSort(sourceToTarget)

    assertEquals(expected, actual)
    assertTrue(actual.isTopologicallySorted(sourceToTarget))
  }

  @Test
  fun priorityQueueChoosesSmallestReadyVertexFirst() {
    /**
     * ```
     *        root
     *      /  |  \
     *    v1   …   v5           all ready at the same time
     * ```
     */
    val deps = edges("rv1", "rv2", "rv3", "rv4", "rv5")

    val expected = listOf("v1", "v2", "v3", "v4", "v5", "r")

    repeat(1000) {
      val shuffled = expected.shuffled()
      val actual = shuffled.topologicalSort(deps)

      assertTrue(actual.isTopologicallySorted(deps))
      assertEquals(expected, actual)
    }
  }

  /**
   * Inside‑component ordering test.
   *
   * One SCC with a deferrable edge is *not* an error, but we want its vertices returned in natural
   * order.
   *
   * ```
   * a  --(deferrable)-->  b
   * ^                     |
   * +--------- strict ----+
   * ```
   *
   * Without vertices.sorted() Tarjan’s pop order is b,a.
   */
  @Test
  fun verticesInsideComponentComeOutInNaturalOrder() {
    val full =
      sortedMapOf(
        "a" to typedSortedSetOf("b"), // deferrable
        "b" to typedSortedSetOf("a"), // strict
      )
    val isDeferrable = { f: String, t: String -> f == "a" && t == "b" }

    val result =
      topologicalSort(
          fullAdjacency = full,
          isDeferrable = isDeferrable,
          onCycle = { fail("cycle") },
        )
        .sortedKeys

    assertEquals(listOf("a", "b"), result)
  }

  @Test
  fun reachabilityWithRoots() {
    // Graph structure:
    // a -> b -> c
    // d -> e
    // f (isolated)
    val fullAdjacency =
      sortedMapOf(
        "a" to typedSortedSetOf("b"),
        "b" to typedSortedSetOf("c"),
        "c" to typedSortedSetOf(),
        "d" to typedSortedSetOf("e"),
        "e" to typedSortedSetOf(),
        "f" to typedSortedSetOf(),
      )

    // Test 1: Start from "a" - should only reach a, b, c
    val resultFromA =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("No cycles expected") },
        roots = typedSortedSetOf("a"),
      )

    assertEquals(setOf("c", "b", "a"), resultFromA.reachableKeys)
    assertEquals(listOf("c", "b", "a"), resultFromA.sortedKeys)

    // Test 2: Start from "d" - should only reach d, e
    val resultFromD =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("No cycles expected") },
        roots = typedSortedSetOf("d"),
      )

    assertEquals(setOf("d", "e"), resultFromD.reachableKeys)
    assertEquals(listOf("e", "d"), resultFromD.sortedKeys)

    // Test 3: Start from "f" - should only reach f
    val resultFromF =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("No cycles expected") },
        roots = typedSortedSetOf("f"),
      )

    assertEquals(setOf("f"), resultFromF.reachableKeys)
    assertEquals(listOf("f"), resultFromF.sortedKeys)

    // Test 4: Multiple roots - should reach union of reachable sets
    val resultFromMultiple =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("No cycles expected") },
        roots = typedSortedSetOf("a", "d"),
      )

    assertEquals(setOf("a", "b", "c", "d", "e"), resultFromMultiple.reachableKeys)
    assertEquals(listOf("c", "b", "a", "e", "d"), resultFromMultiple.sortedKeys)

    // Test 5: Empty roots - should process entire graph
    val resultNoRoots =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("No cycles expected") },
      )

    assertEquals(fullAdjacency.keys, resultNoRoots.reachableKeys)
    assertEquals(listOf("c", "b", "a", "e", "d", "f"), resultNoRoots.sortedKeys)
  }

  @Test
  fun reachabilityWithCycles() {
    // Graph with a cycle reachable from one root but not another
    // a -> b -> c -> b (cycle)
    // d -> e
    val fullAdjacency =
      sortedMapOf(
        "a" to typedSortedSetOf("b"),
        "b" to typedSortedSetOf("c"),
        "c" to typedSortedSetOf("b"), // cycle back to b
        "d" to typedSortedSetOf("e"),
        "e" to typedSortedSetOf(),
      )

    // Test 1: Starting from "a" should detect the cycle
    assertFailsWith<IllegalArgumentException> {
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { cycle -> throw IllegalArgumentException("Cycle detected: $cycle") },
        roots = typedSortedSetOf("a"),
      )
    }

    // Test 2: Starting from "d" should NOT detect the cycle (it's unreachable)
    val resultFromD =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = { _, _ -> false },
        onCycle = { fail("Should not detect cycle from d") },
        roots = typedSortedSetOf("d"),
      )

    assertEquals(setOf("e", "d"), resultFromD.reachableKeys)
    assertEquals(listOf("e", "d"), resultFromD.sortedKeys)
  }

  @Test
  fun reachabilityWithDeferrableCycles() {
    // Graph with a deferrable cycle
    // a -> b --(deferrable)--> c -> b
    val fullAdjacency =
      sortedMapOf(
        "a" to typedSortedSetOf("b"),
        "b" to typedSortedSetOf("c"),
        "c" to typedSortedSetOf("b"),
      )

    val isDeferrable = { from: String, to: String -> from == "b" && to == "c" }

    val result =
      topologicalSort(
        fullAdjacency = fullAdjacency,
        isDeferrable = isDeferrable,
        onCycle = { fail("Should handle deferrable cycle") },
        roots = typedSortedSetOf("a"),
      )

    assertEquals(setOf("a", "b", "c"), result.reachableKeys)
    // b is deferred due to the cycle
    assertEquals(listOf("b"), result.deferredTypes)
    assertTrue(result.sortedKeys.containsAll(listOf("a", "b", "c")))
  }

  @Test
  fun reachabilityPreservesNaturalOrderWithinComponents() {
    // Similar to verticesInsideComponentComeOutInNaturalOrder but with roots
    val full =
      sortedMapOf(
        "a" to typedSortedSetOf("b"), // deferrable
        "b" to typedSortedSetOf("a"), // strict
        "c" to typedSortedSetOf("a"), // c is a root that points to the cycle
      )

    val isDeferrable = { f: String, t: String -> f == "a" && t == "b" }

    val result =
      topologicalSort(
        fullAdjacency = full,
        isDeferrable = isDeferrable,
        onCycle = { fail("cycle") },
        roots = typedSortedSetOf("c"),
      )

    assertEquals(setOf("a", "b", "c"), result.reachableKeys)
    // Should maintain natural order within the component
    val aIndex = result.sortedKeys.indexOf("a")
    val bIndex = result.sortedKeys.indexOf("b")
    assertTrue(aIndex < bIndex, "Expected a before b in sorted order")
  }

  private fun assertTopologicalSort(
    unsorted: List<String>,
    sorted: List<String>,
    vararg edges: String,
  ) {
    val sourceToTarget = edges(*edges)
    assertFalse(unsorted.isTopologicallySorted(sourceToTarget))
    assertTrue(sorted.isTopologicallySorted(sourceToTarget))

    val actual = unsorted.topologicalSort(sourceToTarget)

    assertTrue(actual.isTopologicallySorted(sourceToTarget))
    assertEquals(sorted, actual)
  }

  // stdlib exposes the impl type https://youtrack.jetbrains.com/issue/KT-20972/
  private fun <T : Comparable<T>> typedSortedSetOf(vararg elements: T): SortedSet<T> {
    return sortedSetOf(*elements)
  }

  /** Each string is two characters, source and destination of an edge. */
  private fun edges(vararg edges: String): (String) -> List<String> {
    return { node: String -> edges.filter { it.startsWith(node) }.map { it.substring(1) } }
  }
}
