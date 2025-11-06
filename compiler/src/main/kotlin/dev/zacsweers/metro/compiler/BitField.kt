// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

/**
 * A bit field that can store an arbitrary number of bits using an array of 32-bit integers. Each
 * integer stores 32 bits, allowing support for indices beyond 31.
 */
@JvmInline
internal value class BitField private constructor(private val words: IntArray) {

  constructor(bits: Int = 0) : this(if (bits == 0) EMPTY_ARRAY else intArrayOf(bits))

  /** Returns `true` if the bit at [index] is set. */
  fun isSet(index: Int): Boolean {
    require(index >= 0) { "index must be >= 0" }
    val wordIndex = index / 32
    if (wordIndex >= words.size) return false
    val bitIndex = index % 32
    return words[wordIndex] and (1 shl bitIndex) != 0
  }

  /** Returns true if the bit at [index] is *not* set. */
  fun isUnset(index: Int): Boolean = !isSet(index)

  /** Returns a new set with the bit at [index] set. */
  fun withSet(index: Int): BitField {
    require(index >= 0) { "index must be >= 0" }
    val wordIndex = index / 32
    val bitIndex = index % 32

    // Expand array if necessary
    val newWords =
      if (wordIndex >= words.size) {
        IntArray(wordIndex + 1).also { words.copyInto(it) }
      } else {
        words.copyOf()
      }

    newWords[wordIndex] = newWords[wordIndex] or (1 shl bitIndex)
    return BitField(newWords)
  }

  /** Returns a new set with the bit at [index] cleared. */
  fun withCleared(index: Int): BitField {
    require(index >= 0) { "index must be >= 0" }
    val wordIndex = index / 32
    if (wordIndex >= words.size) return this // Already unset

    val bitIndex = index % 32
    val newWords = words.copyOf()
    newWords[wordIndex] = newWords[wordIndex] and (1 shl bitIndex).inv()
    return BitField(newWords)
  }

  infix fun or(other: BitField): BitField {
    val maxSize = maxOf(words.size, other.words.size)
    val newWords = IntArray(maxSize)
    for (i in 0 until maxSize) {
      val a = if (i < words.size) words[i] else 0
      val b = if (i < other.words.size) other.words[i] else 0
      newWords[i] = a or b
    }
    return BitField(newWords)
  }

  infix fun and(other: BitField): BitField {
    val minSize = minOf(words.size, other.words.size)
    val newWords = IntArray(minSize)
    for (i in 0 until minSize) {
      newWords[i] = words[i] and other.words[i]
    }
    return BitField(newWords)
  }

  infix fun xor(other: BitField): BitField {
    val maxSize = maxOf(words.size, other.words.size)
    val newWords = IntArray(maxSize)
    for (i in 0 until maxSize) {
      val a = if (i < words.size) words[i] else 0
      val b = if (i < other.words.size) other.words[i] else 0
      newWords[i] = a xor b
    }
    return BitField(newWords)
  }

  infix fun or(other: Int) = this or BitField(other)

  infix fun and(other: Int) = this and BitField(other)

  infix fun xor(other: Int) = this xor BitField(other)

  fun toIntList(): List<Int> = words.asList()

  override fun toString(): String {
    return if (words.isEmpty()) {
      "BitField(empty)"
    } else {
      words.joinToString(prefix = "BitField(", postfix = ")") { word ->
        "0b" + word.toUInt().toString(2).padStart(32, '0')
      }
    }
  }

  companion object {
    private val EMPTY_ARRAY = IntArray(0)

    fun Int.toBitField() = BitField(this)

    fun fromIntList(ints: List<Int>): BitField =
      if (ints.isEmpty()) BitField() else BitField(ints.toIntArray())
  }
}
