// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks declarations in the Metro API that are **experimental** &mdash; they are incubating and may
 * be removed or changed in the future. Carefully read documentation of any declaration marked as
 * [ExperimentalMetroApi].
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message =
    "This is an experimental API and may change or be removed in the future." +
      " Make sure you fully read and understand documentation of the declaration that is marked as a delicate API.",
)
public annotation class ExperimentalMetroApi
