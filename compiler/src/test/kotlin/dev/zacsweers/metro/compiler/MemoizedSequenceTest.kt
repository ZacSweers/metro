package dev.zacsweers.metro.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoizedSequenceTest {
  @Test
  fun testConcurrentRead() {
    val memoized = MemoizedSequence(sequenceOf(1, 2, 3, 4, 5, 6))
    val s1 = memoized.iterator()
    val s2 = memoized.iterator()
    val s1read = mutableListOf<Int>()
    val s2read = mutableListOf<Int>()
    while (s1.hasNext() || s2.hasNext()) {
      if (s1.hasNext()) {
        s1read.add(s1.next())
      }
      if (s2.hasNext()) {
        s2read.add(s2.next())
      }
    }
    assertEquals(listOf(1, 2, 3, 4, 5, 6), s1read)
    assertEquals(listOf(1, 2, 3, 4, 5, 6), s2read)
    assertTrue(memoized.isCacheOnly, "Memoized sequence should be cache only after exhaustion.")
  }
}
