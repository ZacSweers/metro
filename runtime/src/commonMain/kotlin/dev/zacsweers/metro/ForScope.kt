// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A common [Qualifier] to indicate a binding is only for a specific [scope]. This is useful for
 * bindings that exist in multiple scoped and you want to disambiguate them from each other.
 *
 * ```
 * @SingleIn(AppScope::class)
 * @DependencyGraph
 * interface AppGraph {
 *   @Provides
 *   @ForScope(AppScope::class)
 *   fun provideHttpClient(): HttpClient = ...
 * }
 * ```
 */
@Target(
  AnnotationTarget.CLASS,
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Qualifier
public annotation class ForScope(val scope: KClass<*>)
