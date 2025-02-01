// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
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
