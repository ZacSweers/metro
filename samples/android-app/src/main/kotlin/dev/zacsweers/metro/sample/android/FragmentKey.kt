// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.fragment.app.Fragment
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding Fragments in a multibinding map. */
@MapKey
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class FragmentKey(val value: KClass<out Fragment>)
