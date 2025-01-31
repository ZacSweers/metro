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
