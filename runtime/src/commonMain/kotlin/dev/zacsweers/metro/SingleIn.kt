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
 * A [Scope] that uses a given [scope] key value to indicate what scope the annotated type is a singleton in.
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
