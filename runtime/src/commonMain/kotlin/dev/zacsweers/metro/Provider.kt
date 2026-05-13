// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.DoubleCheck
import dev.zacsweers.metro.internal.InstanceFactory
import kotlin.jvm.JvmInline

/** A simple class that produces instances of [T]. */
public expect fun interface Provider<T> {
  public operator fun invoke(): T
}

/** A helper function to create a new [Provider] wrapper around a given [provider] lambda. */
@Suppress("NOTHING_TO_INLINE")
public inline fun <T> provider(noinline provider: () -> T): Provider<T> = LambdaProvider(provider)

@PublishedApi
@JvmInline
internal value class LambdaProvider<T>(private val lambda: () -> T) : Provider<T> {
  override fun invoke(): T = lambda()
}

/** Returns a [Provider] wrapper around the given [value]. */
public fun <T> providerOf(value: T): Provider<T> = InstanceFactory(value)

/** Lazily maps [this] Provider's value to another [Provider] of type [R]. */
public inline fun <T, R> Provider<T>.map(crossinline transform: (T) -> R): Provider<R> = Provider {
  transform(invoke())
}

/** Lazily maps [this] Provider's value to another [Provider] of type [R]. */
public inline fun <T, R> Provider<T>.flatMap(
  crossinline transform: (T) -> Provider<R>
): Provider<R> = Provider { transform(invoke())() }

/**
 * Lazily zips [this] Provider's value with another [Provider] of type [R] and returns a Provider of
 * type [V].
 */
public inline fun <T, R, V> Provider<T>.zip(
  other: Provider<R>,
  crossinline transform: (T, R) -> V,
): Provider<V> = Provider { transform(invoke(), other()) }

/** Returns a memoizing [Provider] instance of this provider. */
public fun <T> Provider<T>.memoize(): Provider<T> = DoubleCheck.provider(this)

/** Returns a memoizing [Lazy] instance of this provider. */
public fun <T> Provider<T>.memoizeAsLazy(): Lazy<T> = DoubleCheck.lazy(this)
