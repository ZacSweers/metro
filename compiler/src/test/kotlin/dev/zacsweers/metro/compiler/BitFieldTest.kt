package dev.zacsweers.metro.compiler

import kotlin.test.Test
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
  fun `isSet throws exception for index greater than 31`() {
    val bitField = BitField(0)
    assertFailsWith<IllegalArgumentException> { bitField.isSet(32) }
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
}
