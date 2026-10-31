// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs a suspend graph's cleanup to completion even when the caller is cancelled.
 *
 * Generated code calls this instead of `withContext(NonCancellable)` because this artifact doesn't
 * expose kotlinx-coroutines to the modules that use it. It's inline, so each call compiles to that
 * same `withContext` call.
 */
public suspend inline fun runGraphCleanup(crossinline cleanup: suspend () -> Unit) {
  withContext(NonCancellable) { cleanup() }
}
