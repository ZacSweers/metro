// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.CLASS

/** Marker for generated factories indicating their source callable ID. */
@Target(CLASS)
public annotation class ProvidesCallableId(val callableName: String, val isProperty: Boolean, val isPropertyAccessor: Boolean)
