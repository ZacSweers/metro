/*
 * Copyright (C) 2016 Zac Sweers
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
package dev.zacsweers.lattice.annotations

/**
 * Marks a method on a parameter on an [dependency graph factory][DependencyGraph.Factory] as binding an
 * instance to some key within the graph.
 *
 * For example:
 * ```kotlin
 * @DependencyGraph.Factory
 * interface Factory {
 *   fun newMyGraph(
 *     @BindsInstance foo: Foo,
 *     @BindsInstance @Blue bar: Bar
 *   ): MyGraph
 * }
 * ```
 *
 * will allow clients of the factory to pass their own instances of `Foo` and `Bar`, and those
 * instances can be injected within the graph as `Foo` or `@Blue Bar`, respectively.
 *
 * `@BindsInstance` arguments may not be `null` unless the parameter is annotated with `@Nullable`.
 *
 * For builders, `@BindsInstance` methods must be called before building the graph, unless their
 * parameter is marked `@Nullable`, in which case the graph will act as though it was called with a
 * `null` argument. Primitives, of course, may not be marked `@Nullable`.
 *
 * Binding an instance is equivalent to passing an instance to a module constructor and providing
 * that instance, but is often more efficient. When possible, binding object instances should be
 * preferred to using module instances.
 */
@MustBeDocumented @Target(AnnotationTarget.VALUE_PARAMETER) public annotation class BindsInstance
