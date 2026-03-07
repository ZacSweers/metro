// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

/**
 * Marks classes or methods that exist only for compile-time purposes and should be removed by
 * minification tools like R8/proguard.
 *
 * R8 rules are bundled with the Metro runtime to remove annotated elements via
 * `-assumenosideeffects`.
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class ComptimeOnly
