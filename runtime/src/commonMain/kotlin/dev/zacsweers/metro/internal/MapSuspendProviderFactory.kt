// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroCoroutinesApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider
import kotlin.js.JsStatic
import kotlin.jvm.JvmStatic

/**
 * A [Factory] for `Map<K, SuspendProvider<V>>` bindings. Values are stored directly as
 * [SuspendProvider]s. The compiler adapts ordinary providers with [SyncSuspendProvider].
 */
public class MapSuspendProviderFactory<K : Any, V>
private constructor(private val map: Map<K, SuspendProvider<V>>) :
  Factory<Map<K, SuspendProvider<V>>> {
  /**
   * Returns a `Map<K, SuspendProvider<V>>` whose iteration order is that of the elements given by
   * each of the providers, in the order given at creation.
   */
  override fun invoke(): Map<K, SuspendProvider<V>> = map

  /** A builder for [MapSuspendProviderFactory]. */
  public class Builder<K : Any, V> internal constructor(size: Int) {
    private val map = newLinkedHashMapWithExpectedSize<K, SuspendProvider<V>>(size)

    public fun put(key: K, providerOfValue: SuspendProvider<V>): Builder<K, V> = apply {
      map[key] = providerOfValue
    }

    public fun putAll(mapOfProviders: Provider<Map<K, SuspendProvider<V>>>): Builder<K, V> = apply {
      when (mapOfProviders) {
        is DelegateFactory -> {
          val asDelegateFactory: DelegateFactory<Map<K, SuspendProvider<V>>> = mapOfProviders
          return putAll(asDelegateFactory.getDelegate())
        }
        is MapSuspendProviderFactory<*, *> -> {
          @Suppress("UNCHECKED_CAST")
          map.putAll((mapOfProviders as MapSuspendProviderFactory<K, V>).map)
        }
        else -> {
          map.putAll(mapOfProviders())
        }
      }
    }

    /** Returns a new [MapSuspendProviderFactory]. */
    public fun build(): MapSuspendProviderFactory<K, V> {
      return MapSuspendProviderFactory(map.toUnmodifiableMap())
    }
  }

  public companion object {
    private val EMPTY: MapSuspendProviderFactory<Any, Any> = MapSuspendProviderFactory(emptyMap())

    /** Returns a new [Builder] */
    @JvmStatic
    @JsStatic
    public fun <K : Any, V> builder(size: Int): Builder<K, V> {
      return Builder(size)
    }

    /** Returns a provider of an empty map. */
    // safe contravariant cast
    @JvmStatic
    @JsStatic
    public fun <K, V> empty(): Provider<Map<K, SuspendProvider<V>>> {
      @Suppress("UNCHECKED_CAST")
      return EMPTY as Provider<Map<K, SuspendProvider<V>>>
    }

    /**
     * Returns a [Factory] for a single-entry `Map<K, SuspendProvider<V>>`. The provider is
     * preserved as-is, matching the existing builder-based contract. Skips the [Builder] allocation
     * for the size-1 case.
     */
    @JvmStatic
    @JsStatic
    public fun <K : Any, V> singleton(
      key: K,
      provider: SuspendProvider<V>,
    ): Factory<Map<K, SuspendProvider<V>>> = SingletonMapSuspendProviderFactory(key, provider)
  }
}

private class SingletonMapSuspendProviderFactory<K : Any, V>(key: K, provider: SuspendProvider<V>) :
  Factory<Map<K, SuspendProvider<V>>> {
  private val map: Map<K, SuspendProvider<V>> = SingletonMap(key, provider)

  override fun invoke(): Map<K, SuspendProvider<V>> = map
}
