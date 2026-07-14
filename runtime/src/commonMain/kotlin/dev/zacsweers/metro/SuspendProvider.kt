// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.SuspendInstanceFactory
import kotlin.jvm.JvmInline

/** Produces values of type [T] in a suspend context. */
@ExperimentalMetroCoroutinesApi
public expect fun interface SuspendProvider<T> {
  public suspend operator fun invoke(): T
}

/** Creates a [SuspendProvider] that delegates to [provider]. */
@ExperimentalMetroCoroutinesApi
@Suppress("NOTHING_TO_INLINE")
public inline fun <T> suspendProvider(noinline provider: suspend () -> T): SuspendProvider<T> {
  return when (provider) {
    is SuspendProvider<*> -> {
      @Suppress("UNCHECKED_CAST")
      provider as SuspendProvider<T>
    }
    else -> SuspendFunctionProvider(provider)
  }
}

@ExperimentalMetroCoroutinesApi
@PublishedApi
@JvmInline
internal value class SuspendFunctionProvider<T>(private val function: suspend () -> T) :
  SuspendProvider<T> {
  override suspend fun invoke(): T = function()
}

/** Returns a [SuspendProvider] that always returns [value]. */
@ExperimentalMetroCoroutinesApi
public fun <T> suspendProviderOf(value: T): SuspendProvider<T> = SuspendInstanceFactory(value)

/** Returns a provider that applies [transform] to this provider's value. */
@ExperimentalMetroCoroutinesApi
public inline fun <T, R> SuspendProvider<T>.map(
  crossinline transform: suspend (T) -> R
): SuspendProvider<R> = SuspendProvider { transform(invoke()) }

/**
 * Returns a provider that passes this provider's value to [transform], then invokes the returned
 * provider.
 */
@ExperimentalMetroCoroutinesApi
public inline fun <T, R> SuspendProvider<T>.flatMap(
  crossinline transform: suspend (T) -> SuspendProvider<R>
): SuspendProvider<R> = SuspendProvider { transform(invoke()).invoke() }

/**
 * Returns a provider that invokes this provider and [other] sequentially, then combines their
 * values with [transform].
 */
@ExperimentalMetroCoroutinesApi
public inline fun <T, R, V> SuspendProvider<T>.zip(
  other: SuspendProvider<R>,
  crossinline transform: suspend (T, R) -> V,
): SuspendProvider<V> = SuspendProvider { transform(invoke(), other.invoke()) }
