// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Runs this implemented `Unit` function during graph cleanup. Metro supplies its parameters after
 * closing owned children. Callback order is unspecified. A failure stops the remaining cleanup.
 */
@ExperimentalMetroApi
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class OnGraphClose
