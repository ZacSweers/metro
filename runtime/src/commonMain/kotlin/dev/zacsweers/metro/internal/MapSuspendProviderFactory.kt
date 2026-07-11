// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider

/**
 * A [Factory] for `Map<K, SuspendProvider<V>>` bindings. Values are stored directly as
 * [SuspendProvider]s. The compiler adapts ordinary providers with [SyncSuspendProvider].
 */
public class MapSuspendProviderFactory<K : Any, V : Any>
private constructor(private val map: Map<K, SuspendProvider<V>>) :
  Factory<Map<K, SuspendProvider<V>>> {
  /**
   * Returns a `Map<K, SuspendProvider<V>>` whose iteration order is that of the elements given by
   * each of the providers, in the order given at creation.
   */
  override fun invoke(): Map<K, SuspendProvider<V>> = map

  /** A builder for [MapSuspendProviderFactory]. */
  public class Builder<K : Any, V : Any> internal constructor(size: Int) {
    private val map = newLinkedHashMapWithExpectedSize<K, SuspendProvider<V>>(size)

    public fun put(key: K, providerOfValue: SuspendProvider<V>): Builder<K, V> = apply {
      map[key] = providerOfValue
    }

    public fun putAll(mapOfProviders: Provider<Map<K, SuspendProvider<V>>>): Builder<K, V> = apply {
      map.putAll(mapOfProviders())
    }

    /** Returns a new [MapSuspendProviderFactory]. */
    public fun build(): MapSuspendProviderFactory<K, V> {
      return MapSuspendProviderFactory(map.toUnmodifiableMap())
    }
  }

  public companion object {
    private val EMPTY: MapSuspendProviderFactory<Any, Any> = MapSuspendProviderFactory(emptyMap())

    /** Returns a new [Builder] */
    public fun <K : Any, V : Any> builder(size: Int): Builder<K, V> {
      return Builder(size)
    }

    /** Returns a provider of an empty map. */
    // safe contravariant cast
    public fun <K, V> empty(): Provider<Map<K, SuspendProvider<V>>> {
      @Suppress("UNCHECKED_CAST")
      return EMPTY as Provider<Map<K, SuspendProvider<V>>>
    }
  }
}
