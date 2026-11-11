// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs a suspend graph's cleanup to completion even when the caller is cancelled.
 *
 * Generated code calls this instead of `withContext(NonCancellable)` because this artifact doesn't
 * expose kotlinx-coroutines to the modules that use it. An inline function would compile that same
 * `withContext` call into each graph, so this one isn't inline.
 */
public suspend fun runGraphCleanup(cleanup: suspend () -> Unit) {
  withContext(NonCancellable) { cleanup() }
}
