/*
 * Copyright (C) 2025 Zac Sweers
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

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * Contributes the annotated interface to the given [scope]. This is _only_ applicable to
 * interfaces. The downstream merged graph of the same scope will extend this interface in its
 * implementation.
 *
 * ```
 * @ContributesTo(AppScope::class)
 * interface SomeDependencies {
 *   val httpClient: HttpClient
 * }
 *
 * // Later graph will extend it automatically
 * @DependencyGraph(AppScope::class)
 * interface AppGraph
 * ```
 */
@Target(CLASS)
@Repeatable
public annotation class ContributesTo(val scope: KClass<*>, val replaces: Array<KClass<*>> = [])
