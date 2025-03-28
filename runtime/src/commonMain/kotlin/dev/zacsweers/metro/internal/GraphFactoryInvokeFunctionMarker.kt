// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import kotlin.annotation.AnnotationTarget.FUNCTION

/** Marker for generated factories indicating their invoke function. */
@Target(FUNCTION) public annotation class GraphFactoryInvokeFunctionMarker
