// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

@JvmInline
internal value class BitField(private val bits: Int = 0) {

  /** Returns `true` if the bit at [index] (0–31) is set. */
  fun isSet(index: Int): Boolean {
    require(index in 0..31) { "index must be 0..31" }
    return bits and (1 shl index) != 0
  }

  /** Returns true if the bit at [index] is *not* set. */
  fun isUnset(index: Int): Boolean = !isSet(index)

  /** Returns a new set with the bit at [index] set. */
  fun withSet(index: Int): BitField = BitField(bits or (1 shl index))

  /** Returns a new set with the bit at [index] cleared. */
  fun withCleared(index: Int): BitField = BitField(bits and (1 shl index).inv())

  infix fun or(other: BitField) = BitField(bits or other.bits)

  infix fun and(other: BitField) = BitField(bits and other.bits)

  infix fun xor(other: BitField) = BitField(bits xor other.bits)

  infix fun or(other: Int) = BitField(bits or other)

  infix fun and(other: Int) = BitField(bits and other)

  infix fun xor(other: Int) = BitField(bits xor other)

  override fun toString(): String = "0b" + bits.toUInt().toString(2).padStart(32, '0')

  companion object {
    fun Int.toBitField() = BitField(this)
  }
}
