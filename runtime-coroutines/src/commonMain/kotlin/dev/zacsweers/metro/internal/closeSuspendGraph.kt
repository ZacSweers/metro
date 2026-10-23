// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs cleanup in the caller's context even when its job is cancelled. */
public suspend fun closeSuspendGraph(state: GraphLifecycleState, callbacks: suspend () -> Unit) {
  if (state.claimClose()) {
    withContext(NonCancellable) { callbacks() }
  }
}
