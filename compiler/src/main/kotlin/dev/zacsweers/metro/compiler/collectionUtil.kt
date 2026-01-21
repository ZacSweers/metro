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

import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterMap

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
  return filterTo(mutableSetOf(), predicate)
}

internal fun <T, R> List<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

internal fun <T> List<T>.allElementsAreEqual(): Boolean {
  if (size < 2) return true
  val firstElement = get(0)
  for (i in 1 until size) {
    if (get(i) != firstElement) return false
  }
  return true
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> ScatterMap<K, V>.getValue(key: K): V =
  get(key) ?: throw NoSuchElementException("Key $key is missing in the map.")

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> MutableScatterMap<K, MutableSet<V>>.getAndAdd(key: K, value: V): MutableSet<V> {
  return getOrPut(key, ::mutableSetOf).also { it.add(value) }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> MutableScatterMap<K, MutableScatterSet<V>>.getAndAdd(key: K, value: V): MutableScatterSet<V> {
  return getOrPut(key, ::MutableScatterSet).also { it.add(value) }
}