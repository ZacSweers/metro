// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.MapKey

/** The contribution picker reads required arguments and defaults from the binary declaration. */
@MapKey(unwrapValue = false)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class LibContributionMapKey(val name: String, val version: Int = 7)
