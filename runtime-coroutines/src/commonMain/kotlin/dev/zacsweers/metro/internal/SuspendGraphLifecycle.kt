// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class)

package dev.zacsweers.metro.internal

import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.SuspendCloseableGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Closes the children tracked before [GraphLifecycle.markClosed], then runs [cleanup].
 *
 * Children that implement [SuspendCloseableGraph] close with their suspend `close()`. Others must
 * be [AutoCloseable]. Everything runs in [NonCancellable] so cancelling the caller doesn't
 * interrupt cleanup. A failure still stops cleanup and propagates.
 */
public suspend fun GraphLifecycle.closeSuspending(cleanup: suspend () -> Unit) {
  withContext(NonCancellable) {
    for (child in takeChildren()) {
      if (child is SuspendCloseableGraph) {
        child.close()
      } else {
        (child as AutoCloseable).close()
      }
    }
    cleanup()
  }
}
