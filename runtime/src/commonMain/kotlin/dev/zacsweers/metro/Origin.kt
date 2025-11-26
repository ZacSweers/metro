// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro

import kotlin.reflect.KClass

/**
 * A marker annotation to indicate the origin class of a generated class. This is used by
 * contribution merging in replacements/exclusions, so if a generated contributed binding
 * `GeneratedFoo` denotes an origin of `Foo`, excluding or replacing `Foo` will also exclude
 * `GeneratedFoo`.
 *
 * This is safe for other code generators to use but should not be used by user-written code
 * directly.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class Origin(val value: KClass<*>)
