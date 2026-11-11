// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * Platform-specific guard for [BaseDoubleCheck]'s first value publication.
 *
 * This is runtime-internal machinery, not a general lock abstraction. Implementations may rely on
 * the fact that [BaseDoubleCheck] only uses the guard until `_value` is initialized. Same-thread
 * reentrancy is intentional: recursive providers should reach [reentrantCheck] instead of
 * deadlocking.
 *
 * On native, [GraphChildren] also uses it to serialize single map operations. Any other use has to
 * keep its guarded sections that short, because contended callers share one parker there.
 */
public expect open class DoubleCheckInitGuard()

/**
 * Runs [block] while holding this guard.
 *
 * Recursive calls from the owning thread run [block] immediately; [BaseDoubleCheck] validates the
 * resulting publication with [reentrantCheck].
 */
internal expect inline fun <T> DoubleCheckInitGuard.guarded(block: () -> T): T
