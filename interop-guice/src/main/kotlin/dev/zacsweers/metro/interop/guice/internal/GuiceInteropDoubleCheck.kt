// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.guice.internal

import com.google.inject.Provider as GuiceProvider
import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.interop.guice.asMetroProvider
import dev.zacsweers.metro.internal.BaseDoubleCheck

/** @see BaseDoubleCheck */
public class GuiceInteropDoubleCheck<T : Any>(provider: MetroProvider<T>) :
  BaseDoubleCheck<T>(provider), GuiceProvider<T> {

  override fun get(): T = invoke()

  public companion object {
    public fun <P : GuiceProvider<T>, T : Any> guiceProvider(delegate: P): GuiceProvider<T> {
      if (delegate is GuiceInteropDoubleCheck<*>) {
        return delegate
      }
      return GuiceInteropDoubleCheck(delegate.asMetroProvider())
    }

    public fun <P : MetroProvider<T>, T : Any> fromMetroProvider(provider: P): GuiceProvider<T> {
      if (provider is GuiceProvider<*>) {
        @Suppress("UNCHECKED_CAST")
        return provider as GuiceProvider<T>
      }
      return GuiceInteropDoubleCheck(provider)
    }
  }
}
