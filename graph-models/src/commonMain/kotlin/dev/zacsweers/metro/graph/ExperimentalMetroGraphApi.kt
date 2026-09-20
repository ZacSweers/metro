// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.graph

/**
 * Marks experimental Metro graph models and analysis APIs. These declarations may change or be
 * removed in future releases.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message = "This is an experimental Metro graph API and may change or be removed in the future.",
)
public annotation class ExperimentalMetroGraphApi
