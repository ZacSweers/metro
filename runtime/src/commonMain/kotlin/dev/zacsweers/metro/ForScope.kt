/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
