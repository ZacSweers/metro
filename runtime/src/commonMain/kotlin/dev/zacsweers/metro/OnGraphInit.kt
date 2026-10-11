// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Runs this implemented `Unit` function after ordinary graph initialization. Metro supplies its
 * parameters and finishes the callback before returning the graph. Callback order is unspecified.
 */
@ExperimentalMetroApi
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphInit
