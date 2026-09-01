// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

/** Chooses scheduling behavior independently of the platform's test-mode flag. */
internal interface IndexRequestPolicy {
  fun currentRequestMode(isDispatchThread: Boolean): IndexRequestMode

  fun automaticPresentationRequestMode(): IndexRequestMode

  /** EDT queries return cached data immediately; explicit background queries can wait. */
  data object Production : IndexRequestPolicy {
    override fun currentRequestMode(isDispatchThread: Boolean): IndexRequestMode {
      return if (isDispatchThread) IndexRequestMode.BACKGROUND else IndexRequestMode.SYNCHRONOUS
    }

    override fun automaticPresentationRequestMode(): IndexRequestMode {
      return IndexRequestMode.AUTOMATIC_BACKGROUND
    }
  }
}

/** Describes which generation a query reads and whether a cache miss can schedule or wait. */
internal enum class IndexRequestMode {
  CACHE_ONLY,
  STALE_CACHE_ONLY,
  AUTOMATIC_BACKGROUND,
  BACKGROUND,
  SYNCHRONOUS,
}
