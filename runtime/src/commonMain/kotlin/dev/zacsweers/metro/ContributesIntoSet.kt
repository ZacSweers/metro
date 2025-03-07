// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * Contributes an [IntoSet] binding of the annotated type to the given [scope] as a [boundType] (if
 * specified) or single declared supertype.
 *
 * ```
 * // Implicit supertype is Base
 * @ContributesIntoSet(AppScope::class)
 * @Inject
 * class Impl : Base
 * ```
 *
 * Use [BoundType] to specify a specific bound type if an implicit one is not possible.
 *
 * ```
 * // Explicit supertype is Base
 * @ClassKey(Impl::class)
 * @ContributesIntoSet(AppScope::class, boundType = BoundType<Base>())
 * @Inject
 * class Impl : Base, AnotherBase
 * ```
 *
 * This annotation is _repeatable_, allowing for contributions as multiple bound types. Note that
 * all repeated annotations must use the same [scope].
 *
 * If this declaration is scoped, the [Scope] annotation will be propagated to the generated
 * [IntoSet] declaration.
 *
 * If this declaration is qualified, the [Qualifier] annotation will be propagated to the generated
 * [IntoSet] declaration.
 *
 * @property scope The scope this binding contributes to.
 * @property replaces List of other contributing classes that this binding should replace in the
 *   scope.
 * @property boundType The explicit bound type for this contribution, if not using the implicit
 *   supertype.
 */
@Target(CLASS)
@Repeatable
public annotation class ContributesIntoSet(
  val scope: KClass<*>,
  val replaces: Array<KClass<*>> = [],
  val boundType: BoundType<*> = BoundType<Nothing>(),
)
