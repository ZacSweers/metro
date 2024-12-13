/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.internal

/** The maximum value for a signed 32-bit integer that is equal to a power of 2. */
private const val INT_MAX_POWER_OF_TWO: Int = 1 shl (Int.SIZE_BITS - 2)

/**
 * Returns a new list that is pre-sized to [size], or [emptyList] if empty. The list returned is
 * never intended to grow beyond [size], so adding to a list when the size is 0 is an error.
 */
public fun <T : Any> presizedList(size: Int): MutableList<T> =
  if (size == 0) {
    // Note: cannot use emptyList() here because Kotlin (helpfully) doesn't allow that cast at
    // runtime
    mutableListOf()
  } else {
    ArrayList<T>(size)
  }

/** Returns true if at least one pair of items in [this] are equal. */
public fun List<*>.hasDuplicates(): Boolean =
  if (size < 2) {
    false
  } else {
    size != toSet().size
  }

internal fun <K, V> Map<K, V>.toUnmodifiableMap(): Map<K, V> {
  return if (isEmpty()) {
    // This actually uses a singleton instance
    emptyMap<K, V>()
  } else {
    buildMap(size) { putAll(this@toUnmodifiableMap) }
  }
}

/**
 * Creates a {@link HashSet} instance, with a high enough "intial capcity" that it <em>should</em>
 * hold {@code expectedSize} elements without growth.
 */
internal fun <T> newHashSetWithExpectedSize(expectedSize: Int): HashSet<T> {
  return HashSet<T>(calculateInitialCapacity(expectedSize))
}

/**
 * Creates a {@link LinkedHashMap} instance, with a high enough "initial capacity" that it
 * <em>should</em> hold {@code expectedSize} elements without growth.
 */
public fun <K, V> newLinkedHashMapWithExpectedSize(expectedSize: Int): LinkedHashMap<K, V> {
  return LinkedHashMap<K, V>(calculateInitialCapacity(expectedSize))
}

/**
 * Calculate the initial capacity of a map, based on Guava's
 * [com.google.common.collect.Maps.capacity](https://github.com/google/guava/blob/v28.2/guava/src/com/google/common/collect/Maps.java#L325)
 * approach.
 *
 * Pulled from Kotlin stdlib's collection builders. Slightly different from dagger's but
 * functionally the same.
 */
@PublishedApi
internal fun calculateInitialCapacity(expectedSize: Int): Int =
  when {
    // We are not coercing the value to a valid one and not throwing an exception. It is up to the
    // caller to
    // properly handle negative values.
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < INT_MAX_POWER_OF_TWO -> ((expectedSize / 0.75F) + 1.0F).toInt()
    // any large value
    else -> Int.MAX_VALUE
  }
