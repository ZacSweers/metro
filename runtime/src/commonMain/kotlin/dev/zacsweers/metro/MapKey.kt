/*
 * Copyright (C) 2014 The Dagger Authors.
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
 * Identifies annotation types that are used to associate keys with values returned by
 * [provider callables][Provides] in order to compose a [map][IntoMap].
 *
 * Every provider method annotated with `@Provides` and `@IntoMap` must also have an annotation that
 * identifies the key for that map entry. That annotation's type must be annotated with `@MapKey`.
 *
 * Typically, the key annotation has a single member, whose value is used as the map key.
 *
 * For example, to add an entry to a `Map<SomeEnum, Int>` with key `SomeEnum.FOO`, you could use an
 * annotation called `@SomeEnumKey`:
 * ```
 * @MapKey
 * annotation class SomeEnumKey(val value: SomeEnum)
 *
 * @DependencyGraph
 * interface SomeGraph {
 *   @Provides
 *   @IntoMap
 *   @SomeEnumKey(SomeEnum.FOO)
 *   fun provideFooValue(): Int = 2
 * }
 *
 * @Inject
 * class SomeInjectedType(map: Map<SomeEnum, Int>) {
 *   init {
 *     check(map[SomeEnum.FOO] == 2)
 *   }
 * }
 * ```
 *
 * If `unwrapValue` is true, the annotation's single member can be any type except an array.
 *
 * See [dev.zacsweers.metro] for standard unwrapped map key annotations for keys that are boxed
 * primitives, strings, or classes.
 *
 * ## Annotations as keys
 *
 * If [unwrapValue] is false, then the annotation itself is used as the map key. For example, to add
 * an entry to a `Map<MyMapKey, Int>` map:
 * ```
 * @MapKey(unwrapValue = false)
 * annotation class MyMapKey {
 * String someString();
 * MyEnum someEnum();
 * }
 *
 * @DependencyGraph
 * interface SomeGraph {
 *   @Provides
 *   @IntoMap
 *   @MyMapKey(someString = "foo", someEnum = BAR)
 *   fun provideFooBarValue() = 2
 * }
 *
 * @Inject
 * class SomeInjectedType(map: Map<MyMapKey, Int>) {
 *   init {
 *     check(map[MyMapKeyImpl("foo", MyEnum.BAR)] == 2)
 *   }
 * }
 * ```
 *
 * (Note that there must be a class `MyMapKeyImpl` that implements `MyMapKey` in order to call
 * [Map.get] on the provided map.)
 *
 * @see <a href="https://dagger.dev/multibindings.map-multibindings">Map multibinding</a>
 */
@MustBeDocumented
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class MapKey(
  /**
   * True to use the value of the single member of the annotated annotation as the map key; false to
   * use the annotation instance as the map key.
   *
   * If true, the single member must not be an array.
   */
  val unwrapValue: Boolean = true
)
