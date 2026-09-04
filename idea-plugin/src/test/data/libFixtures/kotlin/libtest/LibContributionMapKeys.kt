// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** The contribution picker reads required arguments and defaults from the binary declaration. */
@MapKey(unwrapValue = false)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LibContributionMapKey(val name: String, val version: Int = 7)

/** Supplies the current implicit-key contract independently of the bootstrap runtime version. */
@Target(AnnotationTarget.ANNOTATION_CLASS)
annotation class LibMapKeyContract(
  val unwrapValue: Boolean = true,
  val implicitClassKey: Boolean = false,
)

/** Its default expression is available to the compiler and absent from consumer Analysis API PSI. */
@LibMapKeyContract(implicitClassKey = true)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LibImplicitClassKey(val value: KClass<*> = Nothing::class)
