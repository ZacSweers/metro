// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.BitField.Companion.fromIntList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitFieldTest {

  @Test
  fun `isSet returns true for set bit`() {
    val bitField = BitField(0).withSet(0)
    assertTrue(bitField.isSet(0))
  }

  @Test
  fun `isSet returns false for unset bit`() {
    val bitField = BitField(0).withSet(0)
    assertFalse(bitField.isSet(1))
  }

  @Test
  fun `isSet throws exception for negative index`() {
    val bitField = BitField(0)
    assertFailsWith<IllegalArgumentException> { bitField.isSet(-1) }
  }

  @Test
  fun `isSet works correctly after multiple sets`() {
    val bitField = BitField(0).withSet(2).withSet(5).withSet(7)
    assertTrue(bitField.isSet(2))
    assertTrue(bitField.isSet(5))
    assertTrue(bitField.isSet(7))
    assertFalse(bitField.isSet(1))
    assertFalse(bitField.isSet(6))
  }

  @Test
  fun `isSet supports indices beyond 31`() {
    val bitField = BitField(0).withSet(32).withSet(50).withSet(100)
    assertTrue(bitField.isSet(32))
    assertTrue(bitField.isSet(50))
    assertTrue(bitField.isSet(100))
    assertFalse(bitField.isSet(31))
    assertFalse(bitField.isSet(33))
    assertFalse(bitField.isSet(99))
  }

  @Test
  fun `withSet expands array as needed`() {
    var bitField = BitField(0)
    for (i in 0..511) {
      bitField = bitField.withSet(i)
    }
    for (i in 0..511) {
      assertTrue(bitField.isSet(i), "Bit $i should be set")
    }
  }

  @Test
  fun `withCleared works correctly`() {
    val bitField = BitField(0).withSet(5).withSet(10).withCleared(5)
    assertFalse(bitField.isSet(5))
    assertTrue(bitField.isSet(10))
  }

  @Test
  fun `withCleared on unset bit is a no-op`() {
    val bitField = BitField(0).withSet(5)
    val cleared = bitField.withCleared(100) // Beyond array size
    assertTrue(cleared.isSet(5))
    assertFalse(cleared.isSet(100))
  }

  @Test
  fun `toIntList and fromIntList roundtrip`() {
    val original = BitField(0).withSet(0).withSet(31).withSet(32).withSet(63).withSet(100)
    val list = original.toIntList()
    val restored = fromIntList(list)

    assertTrue(restored.isSet(0))
    assertTrue(restored.isSet(31))
    assertTrue(restored.isSet(32))
    assertTrue(restored.isSet(63))
    assertTrue(restored.isSet(100))
    assertFalse(restored.isSet(1))
    assertFalse(restored.isSet(30))
  }

  @Test
  fun `toIntList returns empty list for empty BitField`() {
    val bitField = BitField()
    assertTrue(bitField.toIntList().isEmpty())
  }

  @Test
  fun `fromIntList with empty list creates empty BitField`() {
    val bitField = fromIntList(emptyList())
    assertFalse(bitField.isSet(0))
  }

  @Test
  fun `or operation works across multiple words`() {
    val a = BitField(0).withSet(5).withSet(50)
    val b = BitField(0).withSet(10).withSet(100)
    val result = a or b

    assertTrue(result.isSet(5))
    assertTrue(result.isSet(10))
    assertTrue(result.isSet(50))
    assertTrue(result.isSet(100))
  }

  @Test
  fun `and operation works across multiple words`() {
    val a = BitField(0).withSet(5).withSet(50).withSet(100)
    val b = BitField(0).withSet(5).withSet(100)
    val result = a and b

    assertTrue(result.isSet(5))
    assertFalse(result.isSet(50))
    assertTrue(result.isSet(100))
  }

  @Test
  fun `xor operation works across multiple words`() {
    val a = BitField(0).withSet(5).withSet(50).withSet(100)
    val b = BitField(0).withSet(5).withSet(100)
    val result = a xor b

    assertFalse(result.isSet(5))
    assertTrue(result.isSet(50))
    assertFalse(result.isSet(100))
  }

  @Test
  fun `supports exactly 512 indices`() {
    var bitField = BitField()
    // Set every other bit up to 511
    for (i in 0..511 step 2) {
      bitField = bitField.withSet(i)
    }

    // Verify set bits
    for (i in 0..511 step 2) {
      assertTrue(bitField.isSet(i), "Even bit $i should be set")
    }

    // Verify unset bits
    for (i in 1..511 step 2) {
      assertFalse(bitField.isSet(i), "Odd bit $i should be unset")
    }
  }

  @Test
  fun `toIntList produces correct number of words`() {
    val bitField = BitField(0).withSet(0).withSet(100).withSet(200)
    val list = bitField.toIntList()
    // 200 / 32 = 6.25, so we need 7 words (indices 0-6)
    assertEquals(7, list.size)
  }

  @Test
  fun `backwards compatible with single Int constructor`() {
    // Existing code using BitField(someInt) should still work
    val bitField = BitField(0b101010)
    assertTrue(bitField.isSet(1))
    assertTrue(bitField.isSet(3))
    assertTrue(bitField.isSet(5))
    assertFalse(bitField.isSet(0))
    assertFalse(bitField.isSet(2))
  }

  @Test
  fun `isUnset is inverse of isSet`() {
    val bitField = BitField(0).withSet(5).withSet(50)
    assertTrue(bitField.isUnset(0))
    assertFalse(bitField.isUnset(5))
    assertFalse(bitField.isUnset(50))
    assertTrue(bitField.isUnset(100))
  }
}
