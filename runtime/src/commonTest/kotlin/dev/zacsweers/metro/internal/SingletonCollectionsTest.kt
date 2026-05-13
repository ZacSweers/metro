// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SingletonCollectionsTest {

  @Test
  fun setHoldsSingleElement() {
    val set = SingletonSet("only")
    assertEquals(1, set.size)
    assertFalse(set.isEmpty())
    assertTrue(set.contains("only"))
    assertFalse(set.contains("other"))
    assertEquals(listOf("only"), set.toList())
  }

  @Test
  fun setIteratorThrowsAfterSingleNext() {
    val iterator = SingletonSet(42).iterator()
    assertTrue(iterator.hasNext())
    assertEquals(42, iterator.next())
    assertFalse(iterator.hasNext())
    assertFailsWith<NoSuchElementException> { iterator.next() }
  }

  @Test
  fun setIsImmutable() {
    val set: Set<String> = SingletonSet("only")
    // Downcasting to MutableSet must fail at runtime
    @Suppress("UNCHECKED_CAST") val asMutable = set as? MutableSet<String>
    if (asMutable != null) {
      // Some platforms allow the cast (interface erasure) but the operations must throw.
      assertFails { asMutable.add("more") }
      assertFails { asMutable.remove("only") }
      assertFails { asMutable.clear() }
    }
  }

  @Test
  fun setEqualsAndHashCodeFollowSetContract() {
    val set: Set<Int> = SingletonSet(1)
    assertEquals(setOf(1), set)
    assertEquals(setOf(1).hashCode(), set.hashCode())
  }

  @Test
  fun mapHoldsSingleEntry() {
    val map = SingletonMap("k", 1)
    assertEquals(1, map.size)
    assertFalse(map.isEmpty())
    assertTrue(map.containsKey("k"))
    assertFalse(map.containsKey("other"))
    assertTrue(map.containsValue(1))
    assertFalse(map.containsValue(2))
    assertEquals(1, map["k"])
    assertNull(map["other"])
  }

  @Test
  fun mapKeysAndValuesAndEntriesAreReadOnly() {
    val map = SingletonMap("k", 1)
    assertEquals(setOf("k"), map.keys)
    assertEquals(listOf(1), map.values.toList())
    assertEquals(1, map.entries.size)
    val entry = map.entries.single()
    assertEquals("k", entry.key)
    assertEquals(1, entry.value)
  }

  @Test
  fun mapIsImmutable() {
    val map: Map<String, Int> = SingletonMap("k", 1)
    @Suppress("UNCHECKED_CAST") val asMutable = map as? MutableMap<String, Int>
    if (asMutable != null) {
      assertFails { asMutable["k"] = 2 }
      assertFails { asMutable.remove("k") }
      assertFails { asMutable.clear() }
    }
  }

  @Test
  fun mapEqualsAndHashCodeFollowMapContract() {
    val map: Map<String, Int> = SingletonMap("k", 1)
    assertEquals(mapOf("k" to 1), map)
    assertEquals(mapOf("k" to 1).hashCode(), map.hashCode())
  }
}
