// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/** Runs the synchronous cleanup callbacks of a graph's registered children. */
public class CompositeCloseable : AutoCloseable {
  private val callbacks = CloseCallbackRegistry<() -> Unit>()

  /** Registers a child's close callback. */
  public fun add(key: Any, close: () -> Unit): Unit = callbacks.add(key, close)

  /** Releases a child after its cleanup attempt finishes. */
  public fun remove(key: Any): Unit = callbacks.remove(key)

  override fun close() {
    for (entry in callbacks.drain()) {
      entry.callback()
    }
  }
}
