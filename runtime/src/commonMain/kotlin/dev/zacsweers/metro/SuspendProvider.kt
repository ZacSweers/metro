// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A simple class that produces instances of [T] in a suspend context. */
public fun interface SuspendProvider<T> : suspend () -> T {
  public override suspend operator fun invoke(): T
}

/** A helper function to create a new [SuspendProvider] wrapper around a given [provider] lambda. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <T> suspendProvider(noinline provider: suspend () -> T): SuspendProvider<T> =
  SuspendProvider {
    provider()
  }

/** Returns a [SuspendProvider] wrapper around the given [value]. */
public fun <T> suspendProviderOf(value: T): SuspendProvider<T> = SuspendProvider { value }
