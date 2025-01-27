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
 * TODO doc regular usage
 *
 * ## DependencyGraph.Factory
 *
 * If a parameter on an [dependency graph factory][DependencyGraph.Factory] is annotated with this,
 * that instance is added as an instance binding to the graph.
 *
 * For example:
 * ```kotlin
 * @DependencyGraph.Factory
 * interface Factory {
 *   fun newMyGraph(
 *     @Provides foo: Foo,
 *     @Provides @Blue bar: Bar
 *   ): MyGraph
 * }
 * ```
 *
 * will allow clients of the factory to pass their own instances of `Foo` and `Bar`, and those
 * instances can be injected within the graph as `Foo` or `@Blue Bar`, respectively.
 */
@Target(
  AnnotationTarget.FUNCTION,
  AnnotationTarget.FIELD,
  AnnotationTarget.PROPERTY,
  AnnotationTarget.PROPERTY_GETTER,
  AnnotationTarget.VALUE_PARAMETER,
)
public annotation class Provides
