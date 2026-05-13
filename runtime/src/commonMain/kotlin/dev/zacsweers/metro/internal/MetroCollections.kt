// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/** The maximum value for a signed 32-bit integer that is equal to a power of 2. */
private const val INT_MAX_POWER_OF_TWO: Int = 1 shl (Int.SIZE_BITS - 2)

/**
 * Returns a new list that is pre-sized to [size], or [emptyList] if empty. The list returned is
 * never intended to grow beyond [size], so adding to a list when the size is 0 is an error.
 */
internal fun <T : Any> presizedList(size: Int): MutableList<T> =
  if (size == 0) {
    // Note: cannot use emptyList() here because Kotlin (helpfully) doesn't allow that cast at
    // runtime
    mutableListOf()
  } else {
    ArrayList(size)
  }

/** Returns true if at least one pair of items in [this] are equal. */
internal fun List<*>.hasDuplicates(): Boolean =
  if (size < 2) {
    false
  } else {
    size != toSet().size
  }

internal fun <K, V> Map<K, V>.toUnmodifiableMap(): Map<K, V> {
  return if (isEmpty()) {
    // This actually uses a singleton instance
    emptyMap()
  } else {
    buildMap(size) { putAll(this@toUnmodifiableMap) }
  }
}

/**
 * Creates a [LinkedHashMap] instance, with a high enough "initial capacity" that it _should_ hold
 * [expectedSize] elements without growth.
 */
internal fun <K, V> newLinkedHashMapWithExpectedSize(expectedSize: Int): LinkedHashMap<K, V> {
  return LinkedHashMap(calculateInitialCapacity(expectedSize))
}

/**
 * Calculate the initial capacity of a map, based on Guava's
 * [com.google.common.collect.Maps.capacity](https://github.com/google/guava/blob/v28.2/guava/src/com/google/common/collect/Maps.java#L325)
 * approach.
 *
 * Pulled from Kotlin stdlib's collection builders. Slightly different from dagger's but
 * functionally the same.
 */
internal fun calculateInitialCapacity(expectedSize: Int): Int =
  when {
    // We are not coercing the value to a valid one and not throwing an exception. It is up to the
    // caller to properly handle negative values.
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < INT_MAX_POWER_OF_TWO -> ((expectedSize / 0.75F) + 1.0F).toInt()
    // any large value
    else -> Int.MAX_VALUE
  }

/**
 * Read-only single-element [Set]. Mirrors the platform contract of `Collections.singleton(...)` on
 * the JVM but works the same way on every Kotlin target.
 */
internal class SingletonSet<E>(private val element: E) : Set<E> {
  override val size: Int
    get() = 1

  override fun isEmpty(): Boolean = false

  override fun contains(element: E): Boolean = this.element == element

  override fun iterator(): Iterator<E> = SingletonIterator(element)

  override fun containsAll(elements: Collection<E>): Boolean {
    return when (elements.size) {
      0 -> true
      1 -> elements.first() == element
      else -> false
    }
  }

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other !is Set<*>) return false
    return when (other.size) {
      0 -> false
      1 -> other.first() == element
      else -> false
    }
  }

  override fun hashCode(): Int = element.hashCode()

  override fun toString(): String = "[$element]"
}

/**
 * Read-only single-entry [Map]. Mirrors `Collections.singletonMap(...)` cross-platform. [keys],
 * [values], and [entries] are also genuinely read-only.
 */
internal class SingletonMap<K, V>(private val k: K, private val v: V) : Map<K, V> {
  private var _keys: Set<K>? = null
  private var _values: Collection<V>? = null
  private var _entries: Set<Map.Entry<K, V>>? = null
  override val size: Int = 1

  override val keys: Set<K>
    get() = _keys ?: SingletonSet(k).also { _keys = it }

  override val values: Collection<V>
    get() = _values ?: SingletonSet(v).also { _values = it }

  override val entries: Set<Map.Entry<K, V>>
    get() = _entries ?: SingletonSet(SingletonMapEntry(k, v)).also { _entries = it }

  override fun isEmpty(): Boolean = false

  override fun containsKey(key: K): Boolean = k == key

  override fun containsValue(value: V): Boolean = v == value

  override operator fun get(key: K): V? = if (k == key) v else null

  override fun equals(other: Any?): Boolean {
    if (other === this) return true
    if (other !is Map<*, *>) return false
    return when (other.size) {
      1 -> {
        val (otherK, otherV) = other.entries.iterator().next()
        otherK == k && otherV == v
      }
      else -> false
    }
  }

  override fun hashCode(): Int = k.hashCode() xor v.hashCode()

  override fun toString(): String = "{$k=$v}"
}

private class SingletonIterator<T>(private val element: T) : Iterator<T> {
  private var consumed = false

  override fun hasNext(): Boolean = !consumed

  override fun next(): T {
    if (consumed) throw NoSuchElementException()
    consumed = true
    return element
  }
}

/** equals/hashCode/toString match the spec on [Map.Entry]'s doc. */
private class SingletonMapEntry<K, V>(override val key: K, override val value: V) :
  Map.Entry<K, V> {
  override fun equals(other: Any?): Boolean =
    other is Map.Entry<*, *> && other.key == key && other.value == value

  override fun hashCode(): Int = key.hashCode() xor value.hashCode()

  override fun toString(): String = "$key=$value"
}
