// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Indicates this `@Provides` or `@Binds` declaration shall be _private_ to the graph it's provided
 * in. This means the following:
 * - This binding **may not** be exposed directly via accessor.
 * - This binding **will not** be exposed directly to extensions of this graph.
 *
 * This is a mechanism to enforce that annotated bindings cannot be directly leaked. It _may_ be
 * depended on by any bindings _within_ this graph as an implementation detail or encapsulation.
 *
 * This is useful for a few situations.
 * - Users may want certain bindings to stay confined to a given graph, such as a base `HttpClient`.
 * - Users may want to omit certain contributions to multibindings from leaking to extensions.
 * - Sometimes the same type may exist in multiple graph scopes, requiring use of qualifiers like
 *   [@ForScope][ForScope] to disambiguate which one you need. By marking each provision in a graph
 *   as private, you can trust that parent graph instances are not being accidentally leaked to your
 *   extension's scope.
 *
 * ```
 * @DependencyGraph(AppScope::class)
 * interface AppGraph {
 *   @GraphPrivate
 *   @Provides
 *   @SingleIn(AppScope::class)
 *   fun provideCoroutineScope(): CoroutineScope = ...
 *
 *   // Error
 *   val coroutineScope: CoroutineScope
 *
 *   val loggedInGraph: LoggedInGraph
 * }
 *
 * @GraphExtension
 * interface LoggedInGraph {
 *   // Error, no longer implicitly visible
 *   val coroutineScope: CoroutineScope
 * }
 * ```
 */
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.VALUE_PARAMETER,
)
public annotation class GraphPrivate
