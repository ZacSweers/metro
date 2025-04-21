// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/** Marker for generated nested classes for top-level declarations annotated with @Contributes_. */
@Target(CLASS) public annotation class MetroContribution(val scope: KClass<*>)
