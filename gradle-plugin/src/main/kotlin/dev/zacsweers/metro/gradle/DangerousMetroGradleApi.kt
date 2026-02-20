// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/**
 * Marks declarations in the Metro Gradle API that are **dangerous** &mdash; they are extremely
 * experimental, unstable, and should be regarded as wholly unproven in production. Carefully read
 * documentation and [message] of any declaration marked as [DangerousMetroGradleApi].
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.ERROR,
  message =
    "This is a dangerous API and its use requires extreme caution." +
      " Make sure you fully read and understand documentation of the declaration that is marked as a delicate API.",
)
public annotation class DangerousMetroGradleApi(val message: String)
