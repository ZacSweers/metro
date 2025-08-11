// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal


/** Marker for extendable graphs to indicate their generated accessor functions. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class MetroAccessor(val isInstanceAccessor: Boolean = false)
