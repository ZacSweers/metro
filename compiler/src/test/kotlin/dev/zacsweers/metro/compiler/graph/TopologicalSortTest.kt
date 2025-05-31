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
      mapOf(
        "a" to listOf("b"), // deferrable
        "b" to listOf("a"), // strict
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

  /** Each string is two characters, source and destination of an edge. */
  private fun edges(vararg edges: String): (String) -> List<String> {
    return { node: String -> edges.filter { it.startsWith(node) }.map { it.substring(1) } }
  }
}
