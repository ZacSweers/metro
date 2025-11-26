// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A marker annotation to indicate that the annotated graph accessor or injected dependency is
 * optional.
 *
 * ## On Graph Accessors
 *
 * If present, the accessor _must_ have a default `getter` (if it's a property) or function body (if
 * it's a function). If the binding is absent from the graph, the default body will be used. If the
 * binding is present in the graph, this will be overridden and implemented in the graph.
 *
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @OptionalBinding
 *   val httpClient: HttpClient?
 *     get() = null
 *
 *   @OptionalBinding
 *   fun cache(): Cache? = null
 * }
 * ```
 *
 * ## On Injected Parameters
 *
 * If you set the `OptionalBindingBehavior` option to `REQUIRE_OPTIONAL_BINDING`, this annotation is
 * also required on all injected parameters (even if they already declare a default value) for
 * `@Provides` declarations or `@Inject` constructors/top-level functions.
 *
 * This can be desirable for consistency with accessors and/or to otherwise make the behavior more
 * explicit.
 */
@MustBeDocumented
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OptionalBinding
