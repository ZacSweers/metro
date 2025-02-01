// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.annotation.AnnotationTarget.PROPERTY

/** Marker for generated class wrappers for injected top level functions. */
@Target(CLASS, PROPERTY) public annotation class InjectedFunctionClass(val callableName: String)
