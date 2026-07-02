// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Marks declarations in the Metro API that are **delicate**. These APIs are easy to misuse and
 * carry sharp edges. Carefully read the documentation of any declaration marked as
 * [DelicateMetroApi] before using it.
 */
@MustBeDocumented
@Retention(AnnotationRetention.BINARY)
@RequiresOptIn(
  level = RequiresOptIn.Level.WARNING,
  message =
    "This is a delicate API and its use requires care." +
      " Make sure you fully read and understand documentation of the declaration that is marked as a delicate API.",
)
public annotation class DelicateMetroApi
