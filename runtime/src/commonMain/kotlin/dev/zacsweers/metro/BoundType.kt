/*
 * Copyright (C) 2025 Zac Sweers
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
 * This annotation is used with [ContributesBinding], [ContributesIntoSet], and [ContributesIntoMap]
 * to define a bound type. This is analogous to the bound type of a [Binds]-annotated declaration.
 *
 * The generic type [T] should be the bound type, which the compiler plugin will read at
 * compile-time.
 *
 * This is only necessary when the bound type is not implicit on the class itself (i.e. it has
 * multiple declared supertypes or the desired bound type is not the single explicitly declared
 * supertype).
 *
 * ```kotlin
 * @ContributesBinding(AppScope::class, boundType = BoundType<Base>())
 * class Impl : Base, AnotherBase
 * ```
 *
 * For contributions with [Qualifiers][Qualifier], the [T] type argument can be annotated with the
 * qualifier annotation. If none is defined, the compiler will fall back to the qualifier annotation
 * on the class, if any.
 *
 * ```kotlin
 * // Uses the qualifier on `boundType`
 * @ContributesBinding(AppScope::class, boundType = BoundType<@Named("qualified") Base>())
 * class Impl : Base, AnotherBase
 *
 * // Falls back to the class
 * @Named("qualified")
 * @ContributesBinding(AppScope::class, boundType = BoundType<Base>())
 * class Impl : Base, AnotherBase
 * ```
 *
 * For [ContributesIntoMap] bindings, the [T] type argument should also be annotated with a
 * [MapKey]. If none is defined on [T], there _must_ be one on the annotated class to use.
 *
 * ```kotlin
 * @ContributesIntoMap(AppScope::class, boundType = BoundType<@ClassKey(Impl::class) Base>())
 * class Impl : Base, AnotherBase
 * ```
 */
public annotation class BoundType<@Suppress("unused") T : Any>
