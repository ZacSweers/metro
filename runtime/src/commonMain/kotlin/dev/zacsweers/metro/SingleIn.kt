// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A [Scope] that uses a given [scope] key value to indicate what scope the annotated type is a
 * singleton in.
 *
 * Example:
 * ```
 * @DependencyGraph(AppScope::class)
 * interface AppGraph {
 *   @SingleIn(AppScope::class)
 *   @Provides
 *   fun provideHttpClient(): HttpClient = HttpClient()
 * }
 * ```
 *
 * This is a [Repeatable] annotation to declare multiple scopes on
 * [dependency graphs][DependencyGraph], as long as each annotation declares a different [scope].
 *
 * @see AppScope for an out-of-the-box application-wide key.
 */
@Repeatable
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Scope
public annotation class SingleIn(val scope: KClass<*>)
