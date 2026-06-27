// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Binds a type to the declared return type. This is commonly used to bind implementation types to
 * supertypes or to bind them into multibindings.
 *
 * - [Binds]-annotated callable declarations must be abstract. They will never be called at runtime
 *   and are solely signal for the compiler plugin.
 * - Alias [Binds] declarations declare the source binding as their extension receiver type or their
 *   only value parameter type.
 * - Parameterless [Binds] function declarations explicitly claim the returned concrete class's own
 *   `@Inject` constructor binding in the current graph. These are not provider functions and may
 *   not be qualified, scoped, or used as multibindings.
 *
 * ```
 * interface Base
 * @Inject class Impl : Base
 *
 * // In a graph
 * @Binds val Impl.bind: Base
 * // Equivalent function form: @Binds fun bind(base: Impl): Base
 *
 * // Or explicitly claim Impl's @Inject constructor binding
 * @Binds fun bindImpl(): Impl
 *
 * // Or bind into a multibinding
 * @Binds @IntoSet val Impl.bind: Base
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
public annotation class Binds
