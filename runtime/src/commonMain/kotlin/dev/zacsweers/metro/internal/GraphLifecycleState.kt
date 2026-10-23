// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Each generated closeable graph owns one state. The first caller takes responsibility for cleanup.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GraphLifecycleState {
  private val closed = AtomicBoolean(false)

  /** Claims cleanup for either the synchronous or the suspend runtime. */
  public fun claimClose(): Boolean = closed.compareAndSet(false, true)

  /** Claims cleanup before resolving any callback arguments. Recursive calls return immediately. */
  public fun close(callbacks: () -> Unit) {
    if (claimClose()) {
      callbacks()
    }
  }
}
