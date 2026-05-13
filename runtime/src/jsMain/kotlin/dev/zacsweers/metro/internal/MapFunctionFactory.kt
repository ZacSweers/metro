// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.Provider

/**
 * A [Factory] implementation used to implement `Map<K, () -> V>` bindings on Kotlin/JS.
 *
 * On every other Metro target [Provider] extends `() -> T`, so [MapProviderFactory] already
 * produces a map whose values can be invoked as functions. On JS [Provider] is a distinct type, so
 * a `Map<K, Provider<V>>` produced by [MapProviderFactory] cannot satisfy a consumer requesting
 * `Map<K, () -> V>` — the values aren't callable as JS functions. This factory stores the values as
 * `() -> V` directly so the consumer reads honest Function0 lambdas.
 */
public class MapFunctionFactory<K : Any, V>
private constructor(private val contributingMap: Map<K, () -> V>) :
  Factory<Map<K, () -> V>>, Lazy<Map<K, () -> V>> {

  override fun invoke(): Map<K, () -> V> = contributingMap

  override val value: Map<K, () -> V> = contributingMap

  override fun isInitialized(): Boolean = true

  /** A builder for [MapFunctionFactory]. */
  public class Builder<K : Any, V : Any> internal constructor(size: Int) {
    private val map: LinkedHashMap<K, () -> V> = newLinkedHashMapWithExpectedSize(size)

    public fun put(key: K, fn: () -> V): Builder<K, V> = apply { map[key] = fn }

    public fun build(): MapFunctionFactory<K, V> = MapFunctionFactory(map.toUnmodifiableMap())
  }

  public companion object {
    private val EMPTY: Provider<Map<Any, () -> Any>> = InstanceFactory(emptyMap())

    /** Returns a new [Builder]. */
    public fun <K : Any, V : Any> builder(size: Int): Builder<K, V> = Builder(size)

    /** Returns a provider of an empty map. */
    @Suppress("UNCHECKED_CAST")
    public fun <K : Any, V : Any> empty(): Provider<Map<K, () -> V>> =
      EMPTY as Provider<Map<K, () -> V>>

    /**
     * Returns a [Factory] for a single-entry `Map<K, () -> V>`. The lambda is preserved as-is,
     * matching the existing builder-based contract. Skips the [Builder] allocation for the size-1
     * case.
     */
    public fun <K : Any, V : Any> singleton(key: K, fn: () -> V): Factory<Map<K, () -> V>> =
      SingletonMapFunctionFactory(key, fn)
  }
}

private class SingletonMapFunctionFactory<K : Any, V : Any>(key: K, fn: () -> V) :
  Factory<Map<K, () -> V>> {
  private val map: Map<K, () -> V> = SingletonMap(key, fn)

  override fun invoke(): Map<K, () -> V> = map
}
