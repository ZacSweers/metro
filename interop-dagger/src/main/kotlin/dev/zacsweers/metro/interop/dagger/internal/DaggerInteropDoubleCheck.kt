// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.interop.dagger.internal

import dagger.Lazy as DaggerLazy
import dagger.internal.Provider as DaggerProvider
import dev.zacsweers.metro.Provider as MetroProvider
import dev.zacsweers.metro.internal.BaseDoubleCheck
import dev.zacsweers.metro.interop.jakarta.asMetroProvider as jakartaAsMetroProvider
import dev.zacsweers.metro.interop.javax.asMetroProvider as javaxAsMetroProvider
import jakarta.inject.Provider as JakartaProvider
import javax.inject.Provider as JavaxProvider

/** @see BaseDoubleCheck */
public class DaggerInteropDoubleCheck<T : Any>(provider: MetroProvider<T>) :
  BaseDoubleCheck<T>(provider), DaggerProvider<T>, DaggerLazy<T> {

  override fun get(): T = invoke()

  public companion object {
    public fun <P : JavaxProvider<T>, T : Any> javaxProvider(delegate: P): JavaxProvider<T> {
      if (delegate is DaggerInteropDoubleCheck<*>) {
        return delegate
      }
      return DaggerInteropDoubleCheck(delegate.javaxAsMetroProvider())
    }

    public fun <P : JavaxProvider<T>, T : Any> jakartaProvider(delegate: P): JakartaProvider<T> {
      if (delegate is DaggerInteropDoubleCheck<*>) {
        @Suppress("UNCHECKED_CAST")
        return delegate as JakartaProvider<T>
      }
      return DaggerInteropDoubleCheck(delegate.javaxAsMetroProvider())
    }

    public fun <P : JavaxProvider<T>, T : Any> daggerProvider(delegate: P): DaggerProvider<T> {
      if (delegate is DaggerInteropDoubleCheck<*>) {
        @Suppress("UNCHECKED_CAST")
        return delegate as DaggerProvider<T>
      }
      return DaggerInteropDoubleCheck(delegate.javaxAsMetroProvider())
    }

    public fun <P : DaggerProvider<T>, T : Any> lazyFromDaggerProvider(provider: P): DaggerLazy<T> {
      if (provider is DaggerLazy<*>) {
        @Suppress("UNCHECKED_CAST")
        return provider as DaggerLazy<T>
      }
      return DaggerInteropDoubleCheck((provider as JakartaProvider<T>).jakartaAsMetroProvider())
    }

    public fun <P : JavaxProvider<T>, T : Any> lazyFromJavaxProvider(provider: P): DaggerLazy<T> {
      if (provider is DaggerLazy<*>) {
        @Suppress("UNCHECKED_CAST")
        return provider as DaggerLazy<T>
      }
      return DaggerInteropDoubleCheck(provider.javaxAsMetroProvider())
    }

    public fun <P : JakartaProvider<T>, T : Any> lazyFromJakartaProvider(
      provider: P
    ): DaggerLazy<T> {
      if (provider is DaggerLazy<*>) {
        @Suppress("UNCHECKED_CAST")
        return provider as DaggerLazy<T>
      }
      return DaggerInteropDoubleCheck(provider.jakartaAsMetroProvider())
    }

    public fun <P : MetroProvider<T>, T : Any> lazyFromMetroProvider(provider: P): DaggerLazy<T> {
      if (provider is DaggerLazy<*>) {
        @Suppress("UNCHECKED_CAST")
        return provider as DaggerLazy<T>
      }
      return DaggerInteropDoubleCheck(provider)
    }
  }
}
