// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.dagger.internal

import dagger.internal.Factory as DaggerFactory
import dagger.internal.Provider as DaggerProvider
import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.internal.Factory as MetroFactory

public fun <T : Any> DaggerFactory<T>.asMetroFactory(): MetroFactory<T> = MetroFactory(::get)

/**
 * Converts a Metro [MetroProvider] into a [DaggerProvider].
 *
 * @return A [DaggerProvider] that delegates its invocation to the source [MetroProvider].
 */
public fun <T : Any> MetroProvider<T>.asDaggerInternalProvider(): DaggerProvider<T> =
  DaggerProvider(::invoke)
