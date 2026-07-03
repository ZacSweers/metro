// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.InitializedSuspendLazy
import dev.zacsweers.metro.internal.SuspendDoubleCheck
import dev.zacsweers.metro.internal.UnsafeSuspendLazy

@ExperimentalMetroCoroutinesApi
public actual fun <T> suspendLazy(
  mode: LazyThreadSafetyMode,
  initializer: suspend () -> T,
): SuspendLazy<T> =
  when (mode) {
    // Web is single-threaded, but suspend initializers can still interleave across coroutines at
    // suspension points. SYNCHRONIZED routes through SuspendDoubleCheck, whose publication
    // re-check guarantees a single observed value (first completed write wins). PUBLICATION
    // permits redundant computation by contract and NONE promises a single consumer, so both use
    // the unsafe impl.
    LazyThreadSafetyMode.SYNCHRONIZED -> SuspendDoubleCheck.lazy(initializer)
    LazyThreadSafetyMode.PUBLICATION -> UnsafeSuspendLazy(initializer)
    LazyThreadSafetyMode.NONE -> UnsafeSuspendLazy(initializer)
  }

@ExperimentalMetroCoroutinesApi
public actual fun <T> suspendLazyOf(value: T): SuspendLazy<T> = InitializedSuspendLazy(value)
