// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A [Scope] that uses a given [scope] key value to indicate what scope the annotated type is a
 * singleton in.
 *
 * Example:
 * ```
 * @SingleIn(AppScope::class)
 * @DependencyGraph
 * interface AppGraph {
 *   @SingleIn(AppScope::class)
 *   @Provides
 *   fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * @see AppScope for an out-of-the-box app-wide key.
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Scope
public annotation class SingleIn(val scope: KClass<*>)
