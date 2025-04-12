// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Activity
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding Activities in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
annotation class ActivityKey(val value: KClass<out Activity>)
