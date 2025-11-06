// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.jakarta

import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.provider
import jakarta.inject.Provider as JakartaProvider

/**
 * Converts a jakarta [JakartaProvider] into a Metro [MetroProvider].
 *
 * @return A [MetroProvider] that delegates its invocation to the source [JakartaProvider].
 */
public fun <T : Any> JakartaProvider<T>.asMetroProvider(): MetroProvider<T> = provider(::get)

/**
 * Converts a Metro [MetroProvider] into a jakarta [JakartaProvider].
 *
 * @return A [JakartaProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asJakartaProvider(): JakartaProvider<T> =
  JakartaProvider(::invoke)
