// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.jvm.JvmInline

/** A simple class that produces instances of [T] in a suspend context. */
@ExperimentalMetroSuspendApi
public fun interface SuspendProvider<T> : suspend () -> T {
  public override suspend operator fun invoke(): T
}

/** A helper function to create a new [SuspendProvider] wrapper around a given [provider] lambda. */
@ExperimentalMetroSuspendApi
@Suppress("NOTHING_TO_INLINE")
public inline fun <T> suspendProvider(noinline provider: suspend () -> T): SuspendProvider<T> =
  SuspendProvider {
    provider()
  }

/** Returns a [SuspendProvider] wrapper around the given [value]. */
@ExperimentalMetroSuspendApi
public fun <T> suspendProviderOf(value: T): SuspendProvider<T> = InstanceSuspendProvider(value)

@OptIn(ExperimentalMetroSuspendApi::class)
@JvmInline
internal value class InstanceSuspendProvider<T>(private val value: T) : SuspendProvider<T> {
  override suspend fun invoke(): T = value
}

/** Lazily maps [this] SuspendProvider's value to another [SuspendProvider] of type [R]. */
@ExperimentalMetroSuspendApi
public inline fun <T, R> SuspendProvider<T>.map(
  crossinline transform: suspend (T) -> R
): SuspendProvider<R> = SuspendProvider { transform(invoke()) }

/** Lazily maps [this] SuspendProvider's value to another [SuspendProvider] of type [R]. */
@ExperimentalMetroSuspendApi
public inline fun <T, R> SuspendProvider<T>.flatMap(
  crossinline transform: suspend (T) -> SuspendProvider<R>
): SuspendProvider<R> = SuspendProvider { transform(invoke()).invoke() }

/**
 * Lazily zips [this] SuspendProvider's value with another [SuspendProvider] of type [R] and returns
 * a SuspendProvider of type [V].
 */
@ExperimentalMetroSuspendApi
public inline fun <T, R, V> SuspendProvider<T>.zip(
  other: SuspendProvider<R>,
  crossinline transform: suspend (T, R) -> V,
): SuspendProvider<V> = SuspendProvider { transform(invoke(), other.invoke()) }
