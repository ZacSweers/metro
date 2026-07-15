// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A coroutine [Mutex] allows one caller to compute while concurrent callers suspend and re-check
 * publication. The mutex is not reentrant, so [SuspendDoubleCheck] detects cyclic initialization
 * before locking.
 */
public actual open class SuspendDoubleCheckInitGuard actual constructor() {
  internal val mutex = Mutex()
}

internal actual suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T = mutex.withLock { block() }
