// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A graph whose generated [close] function runs its lifecycle cleanup. */
@ExperimentalMetroApi public interface CloseableGraph : AutoCloseable

/** A graph whose generated [close] function can suspend during cleanup. */
@ExperimentalMetroApi
public interface SuspendCloseableGraph {
  /** Closes owned child graphs and runs this graph's cleanup callbacks once. */
  public suspend fun close()
}
