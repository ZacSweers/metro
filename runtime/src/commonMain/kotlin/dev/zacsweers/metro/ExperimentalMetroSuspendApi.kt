// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks declarations that are part of Metro's suspend-provider support as experimental. These APIs
 * are subject to change while the suspend runtime stabilises.
 *
 * Opt in by either annotating the call site with `@OptIn(ExperimentalMetroSuspendApi::class)` or
 * compiling with `-opt-in=dev.zacsweers.metro.ExperimentalMetroSuspendApi`.
 */
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message = "This is part of Metro's experimental suspend-provider support",
)
@Retention(AnnotationRetention.BINARY)
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.TYPEALIAS,
)
public annotation class ExperimentalMetroSuspendApi
