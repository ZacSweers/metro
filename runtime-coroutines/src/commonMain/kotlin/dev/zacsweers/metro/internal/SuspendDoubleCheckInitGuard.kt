// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * Platform-specific guard for [SuspendDoubleCheck]'s first value publication, mirroring
 * [DoubleCheckInitGuard].
 *
 * This is private [SuspendDoubleCheck] machinery, not a reusable lock abstraction. Implementations
 * may rely on the fact that the guard is only used until `_value` is initialized.
 *
 * Unlike [DoubleCheckInitGuard], the guarded block suspends, so thread-based guards don't apply.
 * Both implementations are single-flight (one caller computes, concurrent callers suspend and share
 * the result):
 * - JVM/Native guard with a coroutine Mutex.
 * - JS/Wasm are single-threaded, so the lock is a plain flag plus a FIFO continuation queue, with
 *   no atomics, no parking, no kotlinx.coroutines dependency.
 */
public expect open class SuspendDoubleCheckInitGuard()

/** Runs [block] while holding this guard. */
internal expect suspend fun <T> SuspendDoubleCheckInitGuard.guardedSuspend(
  block: suspend () -> T
): T

/**
 * An identity for the calling coroutine, used to fail fast on reentrant (cyclic) initialization
 * instead of deadlocking on a non-reentrant guard or recursing forever.
 */
internal expect suspend fun SuspendDoubleCheckInitGuard.initCallerIdentity(): Any
