// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider

/**
 * A [Factory] implementation used to implement [Map] bindings. This factory returns a `Map<K,
 * SuspendProvider<V>>` when calling [invoke] (as specified by [Factory]).
 */
public class MapSuspendProviderFactory<K : Any, V : Any>
private constructor(map: Map<K, Provider<V>>) : AbstractMapFactory<K, V, SuspendProvider<V>>(map) {
  /**
   * Returns a `Map<K, SuspendProvider<V>>` whose iteration order is that of the elements given by
   * each of the providers, which are invoked in the order given at creation.
   */
  override fun invoke(): Map<K, SuspendProvider<V>> {
    val result = newLinkedHashMapWithExpectedSize<K, SuspendProvider<V>>(contributingMap().size)
    for (entry in contributingMap().entries) {
      val provider = entry.value
      result[entry.key] = SuspendProvider { provider() }
    }
    return result.toUnmodifiableMap()
  }

  /** A builder for [MapSuspendProviderFactory]. */
  public class Builder<K : Any, V : Any> internal constructor(size: Int) :
    AbstractMapFactory.Builder<K, V, SuspendProvider<V>>(size) {
    public override fun put(key: K, providerOfValue: Provider<V>): Builder<K, V> = apply {
      super.put(key, providerOfValue)
    }

    override fun putAll(mapOfProviders: Provider<Map<K, SuspendProvider<V>>>): Builder<K, V> =
      apply {
        super.putAll(mapOfProviders)
      }

    /** Returns a new [MapSuspendProviderFactory]. */
    public fun build(): MapSuspendProviderFactory<K, V> {
      return MapSuspendProviderFactory(map)
    }
  }

  public companion object {
    private val EMPTY: Provider<Map<Any, Any>> = InstanceFactory(mutableMapOf())

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
