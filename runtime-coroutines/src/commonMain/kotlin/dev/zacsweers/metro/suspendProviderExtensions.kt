// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.SuspendDoubleCheck

/**
 * Returns a provider that caches its first successful result. At most one computation runs at a
 * time; failed or cancelled attempts can be retried.
 */
@ExperimentalMetroCoroutinesApi
public fun <T> SuspendProvider<T>.memoize(): SuspendProvider<T> = SuspendDoubleCheck.provider(this)

/**
 * Returns a [SuspendLazy] that caches this provider's first successful result. At most one
 * computation runs at a time; failed or cancelled attempts can be retried.
 */
@ExperimentalMetroCoroutinesApi
public fun <T> SuspendProvider<T>.memoizeAsLazy(): SuspendLazy<T> = SuspendDoubleCheck.lazy(this)
