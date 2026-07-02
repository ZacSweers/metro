// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import androidx.collection.MutableScatterMap
import androidx.collection.MutableScatterSet
import androidx.collection.ScatterMap

public fun <T, R> Iterable<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

public fun <T, R> Sequence<T>.mapToSet(transform: (T) -> R): Set<R> {
  return mapTo(mutableSetOf(), transform)
}

public fun <T, R> Iterable<T>.flatMapToSet(transform: (T) -> Iterable<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

public fun <T, R> Sequence<T>.flatMapToSet(transform: (T) -> Sequence<R>): Set<R> {
  return flatMapTo(mutableSetOf(), transform)
}

public fun <T> Iterable<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(mutableSetOf(), predicate)
}

public fun <T> List<T>.filterToSet(predicate: (T) -> Boolean): Set<T> {
  return filterTo(mutableSetOf(), predicate)
}

public fun <T> List<T>.allElementsAreEqual(): Boolean {
  if (size < 2) return true
  val firstElement = get(0)
  for (i in 1 until size) {
    if (get(i) != firstElement) return false
  }
  return true
}

@Suppress("NOTHING_TO_INLINE")
public inline fun <K, V> ScatterMap<K, V>.getValue(key: K): V =
  get(key) ?: throw NoSuchElementException("Key $key is missing in the map.")

@IgnorableReturnValue
@Suppress("NOTHING_TO_INLINE")
public inline fun <K, V> MutableScatterMap<K, MutableSet<V>>.getAndAdd(
  key: K,
  value: V,
): MutableSet<V> {
  return getOrPut(key, ::mutableSetOf).also { it.add(value) }
}

@IgnorableReturnValue
@Suppress("NOTHING_TO_INLINE")
public inline fun <K, V> MutableScatterMap<K, MutableScatterSet<V>>.getAndAdd(
  key: K,
  value: V,
): MutableScatterSet<V> {
  return getOrPut(key, ::MutableScatterSet).also { it.add(value) }
}

@JvmName("getAndAddSet")
public fun <K, V> MutableMap<K, MutableSet<V>>.getAndAdd(key: K, value: V) {
  getOrInit(key).also { it.add(value) }
}

@JvmName("getAndAddList")
public fun <K, V> MutableMap<K, MutableList<V>>.getAndAdd(key: K, value: V) {
  getOrInit(key).also { it.add(value) }
}

@IgnorableReturnValue
@JvmName("getOrInitSet")
public fun <K, V> MutableMap<K, MutableSet<V>>.getOrInit(key: K): MutableSet<V> {
  return getOrPut(key, ::mutableSetOf)
}

@IgnorableReturnValue
@JvmName("getOrInitList")
public fun <K, V> MutableMap<K, MutableList<V>>.getOrInit(key: K): MutableList<V> {
  return getOrPut(key, ::mutableListOf)
}
