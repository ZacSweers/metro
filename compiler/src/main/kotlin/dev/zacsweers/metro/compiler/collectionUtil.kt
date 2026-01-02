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
