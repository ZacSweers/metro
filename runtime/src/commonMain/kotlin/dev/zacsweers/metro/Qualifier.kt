// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * A qualifier annotation can be used to _qualify_ or otherwise disambiguate a type on a dependency
 * graph. This is useful for primarily two situations:
 * 1. You have two of the same type on the graph and need to disambiguate them.
 * 2. You have a common type (such as a primitive) on the graph that you want to add more explicit
 *    semantics to.
 *
 * Any annotation annotated with [Qualifier] becomes a "qualifier" annotation that will be
 * recognized and respected by the Metro compiler plugin.
 *
 * ```kotlin
 * @Qualifier
 * annotation class QualifiedInt
 *
 * @DependencyGraph
 * interface AppGraph {
 *   val int: Int
 *   @QualifiedInt val qualifiedInt: Int
 * }
 * ```
 *
 * Metro's runtime contains two out-of-the box qualifiers for common use: [Named] and [ForScope].
 */
@Target(AnnotationTarget.ANNOTATION_CLASS) public annotation class Qualifier
