/*
 * Copyright 2020 The Android Open Source Project
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
package dev.zacsweers.metro.compiler

import androidx.collection.IntList
import androidx.collection.IntSet
import androidx.collection.MutableIntList
import androidx.collection.MutableObjectList
import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ObjectList
import androidx.collection.ScatterMap
import androidx.collection.ScatterSet
import androidx.collection.emptyIntSet
import androidx.collection.emptyObjectList
import androidx.collection.emptyScatterSet
import androidx.collection.objectListOf
import androidx.collection.scatterSetOf
import kotlin.contracts.contract

internal fun <T> Iterable<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(mutableSetOf(), predicate)
}

internal fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T, R> Sequence<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T, R> Iterable<T>.flatMapToSet(transform: (T) -> Iterable<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

internal fun <T, R> Sequence<T>.flatMapToSet(transform: (T) -> Sequence<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

internal fun <T, R : Any> Iterable<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
  return mapNotNullTo(mutableSetOf(), transform)
}

internal fun <T, R : Any> Sequence<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
  return mapNotNullTo(mutableSetOf(), transform)
}

internal inline fun <T, reified R> List<T>.mapToArray(transform: (T) -> R): Array<R> {
  return Array(size) { transform(get(it)) }
}

internal inline fun <T, reified R> Array<T>.mapToArray(transform: (T) -> R): Array<R> {
  return Array(size) { transform(get(it)) }
}

internal inline fun <T, C : Collection<T>, O> C.ifNotEmpty(body: C.() -> O?): O? =
  if (isNotEmpty()) this.body() else null

internal fun <T, R> Iterable<T>.mapToSetWithDupes(transform: (T) -> R): Pair<Set<R>, Set<R>> {
  val dupes = mutableSetOf<R>()
  val destination = mutableSetOf<R>()
  for (item in this) {
    val transformed = transform(item)
    if (!destination.add(transformed)) {
      dupes += transformed
    }
  }
  return destination to dupes
}

internal fun <T> List<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return fastFilterTo(mutableSetOf(), predicate)
}

internal fun <T, R> List<T>.mapToSet(transform: (T) -> R): Set<R> {
  return fastMapTo(mutableSetOf(), transform)
}

internal inline fun <T> List<T>.fastForEach(action: (T) -> Unit) {
  contract { callsInPlace(action) }
  for (index in indices) {
    val item = get(index)
    action(item)
  }
}

internal inline fun <T> List<T>.fastForEachIndexed(action: (Int, T) -> Unit) {
  contract { callsInPlace(action) }
  for (index in indices) {
    val item = get(index)
    action(index, item)
  }
}

internal inline fun <T, R, C : MutableCollection<in R>> List<T>.fastMapTo(
  destination: C,
  transform: (T) -> R,
): C {
  contract { callsInPlace(transform) }
  fastForEach { item -> destination.add(transform(item)) }
  return destination
}

internal inline fun <T> List<T>.fastFilter(predicate: (T) -> Boolean): List<T> {
  contract { callsInPlace(predicate) }
  return fastFilterTo(ArrayList(size), predicate)
}

internal inline fun <T, C : MutableCollection<in T>> List<T>.fastFilterTo(
  destination: C,
  predicate: (T) -> Boolean,
): C {
  contract { callsInPlace(predicate) }
  fastFilteredForEach(predicate) { destination.add(it) }
  return destination
}

internal inline fun <T> List<T>.fastFilterNot(predicate: (T) -> Boolean): List<T> {
  contract { callsInPlace(predicate) }
  return fastFilterNotTo(ArrayList(size), predicate)
}

internal inline fun <T, C : MutableCollection<in T>> List<T>.fastFilterNotTo(
  destination: C,
  predicate: (T) -> Boolean,
): C {
  contract { callsInPlace(predicate) }
  fastForEach { item -> if (!predicate(item)) destination.add(item) }
  return destination
}

internal inline fun <T> List<T>.fastFilteredForEach(
  predicate: (T) -> Boolean,
  action: (T) -> Unit,
) {
  contract {
    callsInPlace(predicate)
    callsInPlace(action)
  }
  fastForEach { item -> if (predicate(item)) action(item) }
}

internal inline fun <T, R> List<T>.fastFilteredMap(
  predicate: (T) -> Boolean,
  transform: (T) -> R,
): List<R> {
  contract {
    callsInPlace(predicate)
    callsInPlace(transform)
  }
  val target = ArrayList<R>(size)
  fastForEach { if (predicate(it)) target += transform(it) }
  return target
}

internal inline fun <T> List<T>.fastAny(predicate: (T) -> Boolean): Boolean {
  contract { callsInPlace(predicate) }
  fastForEach { if (predicate(it)) return true }
  return false
}

internal fun <T> List<T>.allElementsAreEqual(): Boolean {
  if (size < 2) return true
  val firstElement = first()
  return !fastAny { it != firstElement }
}

internal inline fun <T> Iterable<T>.filterToScatterSet(predicate: (T) -> Boolean): ScatterSet<T> {
  contract { callsInPlace(predicate) }
  return if (this is Collection) {
    when (size) {
      0 -> emptyScatterSet()
      1 -> {
        val first = first()
        if (predicate(first)) scatterSetOf(first) else emptyScatterSet()
      }
      else -> {
        val result = MutableScatterSet<T>(size)
        forEach { if (predicate(it)) result.add(it) }
        result
      }
    }
  } else {
    val result = MutableScatterSet<T>()
    forEach { if (predicate(it)) result.add(it) }
    result
  }
}

internal inline fun <T> ScatterSet<T>.filter(predicate: (T) -> Boolean): ScatterSet<T> {
  contract { callsInPlace(predicate) }
  return when (size) {
    0 -> emptyScatterSet()
    1 -> {
      val first = first()
      if (predicate(first)) scatterSetOf(first) else emptyScatterSet()
    }
    else -> {
      val result = MutableScatterSet<T>(size)
      forEach { if (predicate(it)) result.add(it) }
      result
    }
  }
}

internal inline fun <T> ObjectList<T>.filter(predicate: (T) -> Boolean): ObjectList<T> {
  contract { callsInPlace(predicate) }
  return when (size) {
    0 -> emptyObjectList()
    1 -> {
      val first = first()
      if (predicate(first)) objectListOf(first) else emptyObjectList()
    }
    else -> {
      val result = MutableObjectList<T>(size)
      forEach { if (predicate(it)) result.add(it) }
      result
    }
  }
}

internal inline fun <T> ScatterSet<T>.filterNot(predicate: (T) -> Boolean): ScatterSet<T> {
  contract { callsInPlace(predicate) }
  return when (size) {
    0 -> emptyScatterSet()
    1 -> {
      val first = first()
      if (!predicate(first)) scatterSetOf(first) else emptyScatterSet()
    }
    else -> {
      val result = MutableScatterSet<T>(size)
      forEach { if (!predicate(it)) result.add(it) }
      result
    }
  }
}

internal fun <T, R> List<T>.mapNotNullToScatterSet(transform: (T) -> R?): ScatterSet<R> {
  return when (size) {
    0 -> emptyScatterSet()
    1 -> transform(first())?.let(::scatterSetOf) ?: emptyScatterSet()
    else -> {
      val result = MutableScatterSet<R>(size)
      fastForEach { transform(it)?.let(result::add) }
      result
    }
  }
}

internal fun <T, R> List<T>.mapToScatterSet(transform: (T) -> R): ScatterSet<R> {
  return when (size) {
    0 -> emptyScatterSet()
    1 -> scatterSetOf(transform(first()))
    else -> {
      val result = MutableScatterSet<R>(size)
      fastForEach { result.add(transform(it)) }
      result
    }
  }
}

internal fun <T, R> List<T>.mapToObjectList(transform: (T) -> R): ObjectList<R> {
  return when (size) {
    0 -> emptyObjectList()
    1 -> objectListOf(transform(first()))
    else -> {
      val result = MutableObjectList<R>(size)
      fastForEach { result.add(transform(it)) }
      result
    }
  }
}

internal fun <T, R> ObjectList<T>.mapToScatterSet(transform: (T) -> R): ScatterSet<R> {
  return when (size) {
    0 -> emptyScatterSet()
    1 -> scatterSetOf(transform(first()))
    else -> {
      val result = MutableScatterSet<R>(size)
      forEach { result.add(transform(it)) }
      result
    }
  }
}

internal fun <T> List<T>.filterToScatterSet(predicate: (T) -> Boolean): ScatterSet<T> {
  return when (size) {
    0 -> emptyScatterSet()
    1 -> {
      val first = get(0)
      if (predicate(first)) scatterSetOf(first) else emptyScatterSet()
    }
    else -> {
      mutableMapOf(3 to 3).getValue(3)
      val result = MutableScatterSet<T>(size)
      fastForEach { if (predicate(it)) result.add(it) }
      result
    }
  }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> ScatterMap<K, V>.getValue(key: K): V =
  get(key) ?: throw NoSuchElementException("Key $key is missing in the map.")

internal inline fun <K, V> ScatterMap<K, V>.firstValue(predicate: (K, V) -> Boolean): V {
  contract { callsInPlace(predicate) }
  return firstValueOrNull(predicate)
    ?: throw NoSuchElementException("No value matched the predicate.")
}

internal inline fun <K, V> ScatterMap<K, V>.firstValueOrNull(predicate: (K, V) -> Boolean): V? {
  contract { callsInPlace(predicate) }
  forEach { k, v -> if (predicate(k, v)) return v }
  return null
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K> ScatterMap<K, *>.keys(): ScatterSet<K> {
  return mapKeysToScatterSet { it }
}

internal inline fun <K, R> ScatterMap<K, *>.mapKeysToScatterSet(
  transform: (K) -> R
): ScatterSet<R> {
  contract { callsInPlace(transform) }
  val set = MutableScatterSet<R>(size)
  forEachKey { k -> transform(k).let(set::add) }
  return set
}

internal operator fun <K, V> ScatterMap<K, V>.plus(other: ScatterMap<K, V>): ScatterMap<K, V> {
  val map = MutableScatterMap<K, V>(size + other.size)
  map += this
  map += other
  return map
}

internal fun <T> Set<T>.toScatterSet(): ScatterSet<T> {
  return when (size) {
    0 -> emptyScatterSet()
    1 -> scatterSetOf(first())
    else -> {
      val result = MutableScatterSet<T>(size)
      result += this
      result
    }
  }
}

internal fun IntSet?.orEmpty(): IntSet = this ?: emptyIntSet()

internal fun MutableIntList.removeLast(): Int {
  val lastIndex = lastIndex
  return if (lastIndex == -1) {
    throw NoSuchElementException("List is empty.")
  } else {
    removeAt(lastIndex)
  }
}

internal fun <T> MutableObjectList<T>.removeLast(): T {
  val lastIndex = lastIndex
  return if (lastIndex == -1) {
    throw NoSuchElementException("List is empty.")
  } else {
    removeAt(lastIndex)
  }
}

internal fun <T> MutableObjectList<T>.removeFirstOrNull(): T? {
  return if (isEmpty()) {
    null
  } else {
    removeAt(0)
  }
}

internal fun IntList.lastOrNull(): Int? {
  val lastIndex = lastIndex
  return if (lastIndex == -1) {
    null
  } else {
    get(lastIndex)
  }
}

private const val INT_MAX_POWER_OF_TWO: Int = 1 shl (Int.SIZE_BITS - 2)

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
    // caller to
    // properly handle negative values.
    expectedSize < 0 -> expectedSize
    expectedSize < 3 -> expectedSize + 1
    expectedSize < INT_MAX_POWER_OF_TWO -> ((expectedSize / 0.75F) + 1.0F).toInt()
    // any large value
    else -> Int.MAX_VALUE
  }
