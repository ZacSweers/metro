// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A simple class that produces instances of [T]. */
public fun interface Provider<T : Any> {
  public operator fun invoke(): T
}

/** A helper function to create a new [Provider] wrapper around a given [provider] lambda. */
public inline fun <T : Any> provider(crossinline provider: () -> T): Provider<T> {
  return Provider { provider() }
}

/** Creates a [Provider] wrapper around the given [value]. */
public fun <T : Any> providerOf(value: T): Provider<T> {
  return Provider { value }
}
