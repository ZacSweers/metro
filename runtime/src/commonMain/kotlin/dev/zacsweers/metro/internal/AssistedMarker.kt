// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * Marker annotation for generated assisted inject factory classes. This is used internally by Metro
 * to denote factories that were generated for assisted-injected types.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class AssistedMarker
