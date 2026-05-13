// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.annotation.AnnotationTarget.CLASS

/**
 * Declares a default binding type for subtypes that use [ContributesBinding], [ContributesIntoSet],
 * or [ContributesIntoMap].
 *
 * When a subtype has multiple supertypes and doesn't specify an explicit [binding] parameter, Metro
 * normally requires one to disambiguate. With `@DefaultBinding`, the supertype can declare a
 * default so subtypes don't have to.
 *
 * The generic type [T] should be the default bound type, which the compiler plugin will read at
 * compile-time.
 *
 * ```kotlin
 * @DefaultBinding<ThingFactory<*>>
 * interface ThingFactory<T : Thing> {
 *   fun create(): T
 * }
 *
 * // Implicitly binds as ThingFactory<*> even though it has multiple supertypes
 * @ContributesBinding(AppScope::class)
 * @Inject
 * class MyFactory : ThingFactory<MyThing>, AnotherInterface
 * ```
 *
 * If multiple supertypes have `@DefaultBinding`, an explicit [binding] must be specified to
 * disambiguate.
 *
 * Abstract subtypes can supersede the parent default by specifying an explicit [binding] parameter
 * on their contributing annotation.
 */
@ExperimentalMetroApi @Target(CLASS) public annotation class DefaultBinding<@Suppress("unused") T>
