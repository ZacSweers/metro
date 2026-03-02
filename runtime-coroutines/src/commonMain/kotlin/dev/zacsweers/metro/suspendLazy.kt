// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Creates a new [SuspendLazy] instance that uses the specified [mode] for thread safety and the
 * given [initializer] to compute the value on first access.
 *
 * This is the suspend analogue to [lazy].
 */
public expect fun <T> suspendLazy(
  mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
  initializer: suspend () -> T,
): SuspendLazy<T>

/** Returns an already-initialized [SuspendLazy] wrapping the given [value]. */
public expect fun <T> suspendLazyOf(value: T): SuspendLazy<T>
