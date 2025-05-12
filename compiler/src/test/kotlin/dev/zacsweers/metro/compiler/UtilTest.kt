// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class UtilTest {

  @Test
  fun `compareTo returns zero for equal lists`() {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(1, 2, 3)

    val result = list1.compareTo(list2)

    assertEquals(0, result)
  }

  @Test
  fun `compareTo returns positive when first list is larger`() {
    val list1 = listOf(1, 2, 3, 4)
    val list2 = listOf(1, 2, 3)

    val result = list1.compareTo(list2)

    assertTrue(result > 0)
  }

  @Test
  fun `compareTo returns negative when second list is larger`() {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(1, 2, 3, 4)

    val result = list1.compareTo(list2)

    assertTrue(result < 0)
  }

  @Test
  fun `compareTo compares element values`() {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(1, 2, 4)

    val result = list1.compareTo(list2)

    assertTrue(result < 0)
  }

  @Test
  fun `compareTo compares reversed order element values`() {
    val list1 = listOf(1, 2, 4)
    val list2 = listOf(1, 2, 3)

    val result = list1.compareTo(list2)

    assertTrue(result > 0)
  }

  @Test
  fun `compareTo returns zero for empty lists`() {
    val list1 = emptyList<Int>()
    val list2 = emptyList<Int>()

    val result = list1.compareTo(list2)

    assertEquals(0, result)
  }

  @Test
  fun `compareTo with same-size lists returns result based on element comparison`() {
    val list1 = listOf(1, 2, 3)
    val list2 = listOf(1, 3, 2)

    val result = list1.compareTo(list2)

    assertTrue(result < 0)
  }
}
