// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.SuspendDoubleCheck

/** Returns a memoizing [SuspendProvider] instance of this provider. */
@ExperimentalMetroCoroutinesApi
public fun <T> SuspendProvider<T>.memoize(): SuspendProvider<T> = SuspendDoubleCheck.provider(this)

/** Returns a memoizing [SuspendLazy] instance of this provider. */
@ExperimentalMetroCoroutinesApi
public fun <T> SuspendProvider<T>.memoizeAsLazy(): SuspendLazy<T> = SuspendDoubleCheck.lazy(this)
