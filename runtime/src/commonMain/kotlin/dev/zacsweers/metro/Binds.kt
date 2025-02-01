// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

/**
 * Binds a given type as a type-assignable return type. This is commonly used to bind implementation
 * types to supertypes or to bind them into multibindings.
 * - [Binds]-annotated callable declarations must be abstract. They will never be called at runtime
 *   and are solely signal for the compiler plugin.
 * - [Binds]-annotated callable declarations may declare the source binding as their extension
 *   receiver type.
 *
 * ```
 * interface Base
 * class Impl : Base
 *
 * // In a graph
 * @Binds fun Impl.bind: Base
 *
 * // Or bind into a multibinding
 * @Binds @IntoSet fun Impl.bind: Base
 * ```
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
public annotation class Binds
