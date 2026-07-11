// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Creates a [SuspendLazy] that runs [initializer] on first access.
 *
 * [LazyThreadSafetyMode.SYNCHRONIZED] runs one initializer while other callers wait.
 * [LazyThreadSafetyMode.PUBLICATION] allows initializers to overlap and caches one result.
 * [LazyThreadSafetyMode.NONE] does not coordinate concurrent callers.
 */
@ExperimentalMetroCoroutinesApi
public expect fun <T> suspendLazy(
  mode: LazyThreadSafetyMode = LazyThreadSafetyMode.SYNCHRONIZED,
  initializer: suspend () -> T,
): SuspendLazy<T>

/** Returns an initialized [SuspendLazy] containing [value]. */
@ExperimentalMetroCoroutinesApi public expect fun <T> suspendLazyOf(value: T): SuspendLazy<T>
