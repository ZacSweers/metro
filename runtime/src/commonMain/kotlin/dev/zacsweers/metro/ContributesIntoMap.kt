// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * Contributes an [IntoMap] binding of the annotated type to the given [scope] as a [boundType] (if
 * specified) or single declared supertype. A [MapKey] _must_ be declared either on the annotated
 * class or on the [boundType].
 *
 * ```
 * // Implicit supertype is Base
 * @ClassKey(Impl::class)
 * @ContributesIntoMap(AppScope::class)
 * @Inject
 * class Impl : Base
 * ```
 *
 * Use [BoundType] to specify a specific bound type if an implicit one is not possible.
 *
 * ```
 * // Explicit supertype is Base
 * @ClassKey(Impl::class)
 * @ContributesIntoMap(AppScope::class, boundType = BoundType<Base>())
 * @Inject
 * class Impl : Base, AnotherBase
 * ```
 *
 * [BoundType]'s type argument can also be annotated with a [MapKey].
 *
 * ```
 * // Explicit supertype is Base
 * @ContributesIntoMap(AppScope::class, boundType = BoundType<@ClassKey(Impl::class) Base>())
 * @Inject
 * class Impl : Base, AnotherBase
 * ```
 *
 * This annotation is _repeatable_, allowing for contributions as multiple bound types. Note that
 * all repeated annotations must use the same [scope].
 *
 * If this declaration is scoped, the [Scope] annotation will be propagated to the generated
 * [IntoMap] declaration.
 *
 * If this declaration is qualified, the [Qualifier] annotation will be propagated to the generated
 * [IntoMap] declaration.
 */
@Target(CLASS)
@Repeatable
public annotation class ContributesIntoMap(
  val scope: KClass<*>,
  val replaces: Array<KClass<*>> = [],
  val boundType: BoundType<*> = BoundType<Nothing>(),
)
