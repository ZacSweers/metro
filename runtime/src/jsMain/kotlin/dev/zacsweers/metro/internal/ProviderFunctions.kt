// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroCoroutinesApi
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SuspendProvider

/**
 * Returns [provider] as a `() -> T` function.
 *
 * On every other Metro target [Provider] extends `() -> T`. On JS it doesn't, and a [Provider]
 * instance isn't callable as a JS function. Metro-generated code calls this instead of creating its
 * own wrapper lambda at every site that needs a function.
 */
public fun <T> providerAsFunction(provider: Provider<T>): () -> T = { provider() }

/** Returns [provider] as a `suspend () -> T` function. See [providerAsFunction]. */
@ExperimentalMetroCoroutinesApi
public fun <T> suspendProviderAsFunction(provider: SuspendProvider<T>): suspend () -> T = {
  provider()
}

/**
 * Returns a provider of [providers]' map with each entry turned into a `() -> V` function. Map
 * bindings with function values use this on JS, where a [Provider] isn't callable as a function.
 * The functions are built on the first call, and every later call returns the same map.
 */
public fun <K : Any, V> providerMapAsFunctions(
  providers: Provider<Map<K, Provider<V>>>,
): Provider<Map<K, () -> V>> = ConvertedMapProvider(providers) { providerAsFunction(it) }

/** Like [providerMapAsFunctions], for maps of [SuspendProvider]s. */
@ExperimentalMetroCoroutinesApi
public fun <K : Any, V> suspendProviderMapAsFunctions(
  providers: Provider<Map<K, SuspendProvider<V>>>,
): Provider<Map<K, suspend () -> V>> =
  ConvertedMapProvider(providers) { suspendProviderAsFunction(it) }

/** Converts each entry of [providers]' map once and returns the converted map on every call. */
private class ConvertedMapProvider<K : Any, P, F>(
  private val providers: Provider<Map<K, P>>,
  private val convert: (P) -> F,
) : Provider<Map<K, F>> {
  private var converted: Map<K, F>? = null

  override fun invoke(): Map<K, F> {
    val cached = converted
    if (cached != null) {
      return cached
    }
    val created = providers().mapValues { (_, provider) -> convert(provider) }.toUnmodifiableMap()
    converted = created
    return created
  }
}
