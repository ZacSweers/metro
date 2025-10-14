// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Indicates that the annotated Metro API is delicate and should be used carefully. See its docs for
 * more details.
 */
@RequiresOptIn
@Retention(AnnotationRetention.BINARY)
@Target(FUNCTION)
public annotation class DelicateMetroRetainedApi
