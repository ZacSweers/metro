/*
 * Copyright (C) 2015 The Dagger Authors.
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
package dev.zacsweers.lattice

/**
 * Annotates abstract module methods that declare multibindings.
 *
 * You can declare that a multibound set or map is bound by annotating an abstract module method
 * that returns the set or map you want to declare with `@Multibinds`.
 *
 * You do not have to use `@Multibinds` for sets or maps that have at least one contribution, but
 * you do have to declare them if they may be empty.
 *
 * ```
 * @DependencyGraph interface MyGraph {
 *   @Multibinds aSet(): Set<Foo>
 *   @Multibinds @MyQualifier aQualifiedSet(): Set<Foo>
 *   @Multibinds aMap(): Map<String, Foo>
 *   @Multibinds @MyQualifier aQualifiedMap(): Map<String, Foo>
 *
 *   @Provides
 *   fun usesMultibindings(set: Set<Foo>, @MyQualifier map: Map<String, Foo>): Any {
 *     return …
 *   }
 * }
 * ```
 *
 * A given set or map multibinding can be declared any number of times without error. Dagger never
 * implements or calls any `@Multibinds` methods.
 *
 * @see <a href="https://dagger.dev/multibindings">Multibindings</a>
 */
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Multibinds
