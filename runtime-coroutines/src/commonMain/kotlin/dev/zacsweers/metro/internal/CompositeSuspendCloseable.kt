// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Runs the cleanup callbacks of a suspend-closeable graph's registered children. */
public class CompositeSuspendCloseable {
  private val callbacks = CloseCallbackRegistry<suspend () -> Unit>()

  /** Registers a child's close callback. */
  public fun add(key: Any, close: suspend () -> Unit): Unit = callbacks.add(key, close)

  /** Releases a child after its cleanup attempt finishes. */
  public fun remove(key: Any): Unit = callbacks.remove(key)

  /** Closes children sequentially and stops at the first failure. */
  public suspend fun close() {
    for (entry in callbacks.drain()) {
      entry.callback()
    }
  }
}

/** Finishes graph cleanup even when the caller's coroutine is cancelled. */
public suspend fun runGraphClose(block: suspend () -> Unit): Unit =
  withContext(NonCancellable) { block() }
