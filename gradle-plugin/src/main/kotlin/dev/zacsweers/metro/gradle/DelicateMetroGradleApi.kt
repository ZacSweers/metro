// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/**
 * Marks declarations in the Metro Gradle API that are **delicate** &mdash; they have narrow
 * use-cases and should be used with care in general code. Carefully read documentation and
 * [message] of any declaration marked as [DelicateMetroGradleApi].
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message =
    "This is a delicate API and its use requires care." +
      " Make sure you fully read and understand documentation of the declaration that is marked as a delicate API.",
)
public annotation class DelicateMetroGradleApi(val message: String)
