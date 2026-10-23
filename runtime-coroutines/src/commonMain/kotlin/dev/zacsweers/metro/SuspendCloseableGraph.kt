// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/** A graph whose generated cleanup can suspend. Requires Metro's suspend provider support. */
@ExperimentalMetroApi
public interface SuspendCloseableGraph {
  /** Closes owned children and runs cleanup callbacks on the first call. */
  public suspend fun close()
}
