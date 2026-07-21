// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks APIs that are part of Metro's experimental coroutines support.
 *
 * Opt in by either annotating the call site with `@OptIn(ExperimentalMetroCoroutinesApi::class)` or
 * compiling with `-opt-in=dev.zacsweers.metro.ExperimentalMetroCoroutinesApi`.
 */
@MustBeDocumented
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message = "This is part of Metro's experimental coroutines support",
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalMetroCoroutinesApi
