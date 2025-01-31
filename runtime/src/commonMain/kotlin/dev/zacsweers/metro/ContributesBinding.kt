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

import kotlin.annotation.AnnotationTarget.CLASS
import kotlin.reflect.KClass

/**
 * Contributes a binding of the annotated type to the given [scope] as a [boundType] (if specified)
 * or single declared supertype.
 *
 * ```
 * // Implicit supertype is Base
 * @ContributesBinding(AppScope::class)
 * @Inject
 * class Impl : Base
 * ```
 *
 * Use [BoundType] to specify a specific bound type if an implicit one is not possible.
 *
 * ```
 * // Explicit supertype is Base
 * @ContributesBinding(AppScope::class, boundType = BoundType<Base>())
 * @Inject
 * class Impl : Base, AnotherBase
 * ```
 *
 * This annotation is _repeatable_, allowing for contributions as multiple bound types. Note that
 * all repeated annotations must use the same [scope].
 *
 * If this declaration is scoped, the [Scope] annotation will be propagated to the generated [Binds]
 * declaration.
 *
 * If this declaration is qualified, the [Qualifier] annotation will be propagated to the generated
 * [Binds] declaration.
 */
@Target(CLASS)
@Repeatable
public annotation class ContributesBinding(
  val scope: KClass<*>,
  val replaces: Array<KClass<*>> = [],
  val boundType: BoundType<*> = BoundType<Nothing>(),
)
