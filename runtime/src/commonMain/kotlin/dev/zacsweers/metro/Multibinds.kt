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
package dev.zacsweers.metro

/**
 * Annotates abstract graph members that declare multibindings.
 *
 * You can declare that a multibound set or map is bound by annotating an abstract graph member that
 * returns the set or map you want to declare with `@Multibinds`.
 *
 * You do not have to use `@Multibinds` for sets or maps that have at least one contribution, but
 * you do have to declare them if they may be empty. Empty multibindings are an _error_ by default,
 * you must set [allowEmpty] to true to allow empty multibindings.
 *
 * ```
 * @DependencyGraph interface MyGraph {
 *   @Multibinds(allowEmpty = true) fun aSet(): Set<Foo>
 *   @Multibinds(allowEmpty = true) @MyQualifier val aQualifiedSet: Set<Foo>
 *   @Multibinds(allowEmpty = true) fun aMap(): Map<String, Foo>
 *   @Multibinds(allowEmpty = true) @MyQualifier val aQualifiedMap: Map<String, Foo>
 *
 *   @Provides
 *   fun usesMultibindings(set: Set<Foo>, @MyQualifier map: Map<String, Foo>): Any {
 *     return …
 *   }
 * }
 * ```
 *
 * A given set or map multibinding can be declared any number of times without error. Metro
 * implements these declarations to return the declared multibinding.
 */
@MustBeDocumented
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Multibinds(val allowEmpty: Boolean = false)
