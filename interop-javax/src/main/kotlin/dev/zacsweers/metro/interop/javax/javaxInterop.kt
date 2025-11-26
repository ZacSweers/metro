// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.javax

import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.provider
import javax.inject.Provider as JavaxProvider

/**
 * Converts a javax [JavaxProvider] into a Metro [MetroProvider].
 *
 * @return A [MetroProvider] that delegates its invocation to the source [JavaxProvider].
 */
public fun <T : Any> JavaxProvider<T>.asMetroProvider(): MetroProvider<T> = provider(::get)

/**
 * Converts a Metro [MetroProvider] into a javax [JavaxProvider].
 *
 * @return A [JavaxProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asJavaxProvider(): JavaxProvider<T> = JavaxProvider(::invoke)
