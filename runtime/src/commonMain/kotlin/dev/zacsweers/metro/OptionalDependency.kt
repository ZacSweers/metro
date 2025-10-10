// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A marker annotation to indicate that the annotated graph accessor is optional.
 *
 * If present, the accessor _must_ have a default `getter` (if it's a property) or function body (if
 * it's a function). If the binding is absent from the graph, the default body will be used. If the
 * binding is present in the graph, this will be overridden and implemented in the graph.
 *
 * ```kotlin
 * @DependencyGraph
 * interface AppGraph {
 *   @OptionalDependency
 *   val httpClient: HttpClient?
 *     get() = null
 *
 *   @OptionalDependency
 *   fun cache(): Cache? = null
 * }
 * ```
 *
 * If you set the `OptionalDependencyBehavior` option to
 * `REQUIRE_OPTIONAL_DEPENDENCY`, this annotation is also required on all injected
 * parameters (even if they already declare a default value). This can be desirable for consistency
 * with accessors and/or to otherwise make the behavior more explicit.
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
public annotation class OptionalDependency
