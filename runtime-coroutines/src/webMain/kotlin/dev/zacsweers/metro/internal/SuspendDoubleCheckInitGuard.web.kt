// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.coroutines.coroutineContext

// Web doesn't do multithreading. Interleaved suspend initializations are handled by
// SuspendDoubleCheck's post-compute publication re-check (first completed write wins). This keeps
// the web variants of this module free of any kotlinx.coroutines dependency.
public actual open class SuspendDoubleCheckInitGuard actual constructor()

internal actual suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T = block()

internal actual suspend fun SuspendDoubleCheckInitGuard.initCallerIdentity(): Any = coroutineContext
