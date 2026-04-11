// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle

/**
 * Marks declarations in the Metro Gradle API that require IDE support to be enabled to be useful in
 * a project. If you are willing to do this, you can opt in this annotation!
 *
 * @see <a href="https://zacsweers.github.io/metro/latest/installation/#ide-support">Docs for how to
 *   enable IDE support</a>
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message =
    "Marks declarations in the Metro Gradle API that require IDE support to be enabled to be useful in a project. " +
      "If you are willing to do this, you can opt in this annotation! " +
      "See https://zacsweers.github.io/metro/latest/installation/#ide-support for setup instructions.",
)
public annotation class RequiresIdeSupport
