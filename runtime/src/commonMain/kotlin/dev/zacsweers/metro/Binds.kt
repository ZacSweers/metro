/*
 * Copyright (C) 2024 Zac Sweers
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
 * Binds a given type as a type-assignable return type. This is commonly used to bind implementation types
 * to supertypes or to bind them into multibindings.
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
