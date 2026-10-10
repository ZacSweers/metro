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
