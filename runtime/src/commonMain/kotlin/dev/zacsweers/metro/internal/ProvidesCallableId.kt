// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.CLASS

/** Marker for generated factories indicating their source callable ID. */
@Target(CLASS)
public annotation class ProvidesCallableId(
  val callableName: String,
  val isPropertyAccessor: Boolean,
  // Store original offsets for error reporting. When constructing the "real" function from the
  // mirror function, we'll read these back.
  val startOffset: Int,
  val endOffset: Int,
)
