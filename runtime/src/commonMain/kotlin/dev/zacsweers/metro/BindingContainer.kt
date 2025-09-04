// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * Binding containers are classes or interfaces that contain _binding_ callables annotated with
 * [Provides] or [Binds].
 *
 * ## Usage with [DependencyGraph.Factory]
 *
 * Instances of binding containers must be added to [dependency graphs][DependencyGraph] via
 * [DependencyGraph.Factory] parameters annotated with [@Includes][Includes].
 *
 * ```
 * @BindingContainer
 * class NetworkBindings(val baseUrl: String) {
 *   @Provides fun provideHttpClient(): HttpClient = HttpClient(baseUrl)
 * }
 *
 * @DependencyGraph
 * interface AppGraph {
 *   @DependencyGraph.Factory
 *   interface Factory {
 *     fun create(@Includes networkBindings: NetworkBindings): AppGraph
 *   }
 * }
 * ```
 *
 * ## Usage with [DependencyGraph.bindingContainers]
 *
 * In certain cases, you can declare the class in [DependencyGraph.bindingContainers]:
 * - interfaces or abstract classes with _only_ [Binds] providers or companion object [Provides]
 *   providers.
 * - simple, non-generic classes with a public, no-arg constructor
 * - object classes
 *
 * ```
 * @BindingContainer
 * abstract class NetworkBindings {
 *   @Provides abstract fun RealHttpClient.bind: HttpClient
 * }
 *
 * @DependencyGraph(bindingContainers = [NetworkBindings::class])
 * interface AppGraph
 * ```
 *
 * Binding containers added via [DependencyGraph.Factory] parameters do _not_ need to be declared in
 * [DependencyGraph.bindingContainers].
 *
 * Binding containers may be _contributed_ with [ContributesTo] and can replace other contributed
 * binding containers.
 *
 * ## Notes
 * - It is an error to annotate companion objects, annotation classes, or enum classes/entries.
 *     - companion object providers within a binding container are automatically included.
 * - Unannotated callables in binding containers are ignored.
 * - Enclosing classes of [Binds] or [Provides] providers do not need to be annotated with
 *   [BindingContainer] for Metro to process them. This annotation is purely for reference to
 *   [DependencyGraph.Factory] and the ability to use [includes].
 * - Binding containers in Metro are analogous to Dagger's `@Module`.
 *
 * @property includes An optional array of more binding containers that this one may transitively
 *   include. Note that these must abide by the same requirements as arguments to
 *   [DependencyGraph.bindingContainers].
 */
@Target(AnnotationTarget.CLASS)
public annotation class BindingContainer(val includes: Array<KClass<*>> = [])
