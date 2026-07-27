// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import dev.zacsweers.metro.internal.SafePublicationSuspendLazy
import dev.zacsweers.metro.internal.SuspendDoubleCheck
import dev.zacsweers.metro.internal.UnsafeSuspendLazy

@ExperimentalMetroCoroutinesApi
public actual fun <T> suspendLazy(
  mode: LazyThreadSafetyMode,
  initializer: suspend () -> T,
): SuspendLazy<T> =
  when (mode) {
    LazyThreadSafetyMode.SYNCHRONIZED -> SuspendDoubleCheck.lazy { initializer() }
    LazyThreadSafetyMode.PUBLICATION -> SafePublicationSuspendLazy(initializer)
    LazyThreadSafetyMode.NONE -> UnsafeSuspendLazy(initializer)
  }
