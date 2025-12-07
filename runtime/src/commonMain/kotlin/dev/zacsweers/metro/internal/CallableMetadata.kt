// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.CLASS

/** Marker for generated factories or binding mirrors indicating their source callable ID. */
@Target(CLASS, AnnotationTarget.FUNCTION)
public annotation class CallableMetadata(
  val callableName: String,
  val propertyName: String,
  // Store original offsets for error reporting. When constructing the "real" declaration from the
  // mirror function, we'll read these back.
  val startOffset: Int,
  val endOffset: Int,
  // The name for the generated newInstance function. For properties, this may be the property
  // name (for backing fields or "is"-prefixed properties) or "get${propertyName}" (for getters).
  val newInstanceName: String = "",
)
