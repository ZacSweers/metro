// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Activity
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for binding an Activity in a multibinding map.
 *
 * @property value The Activity class used as the key. Defaults to the annotated class when omitted.
 */
@MapKey(implicitClassKey = true)
@Target(AnnotationTarget.CLASS)
public annotation class ActivityKey(val value: KClass<out Activity> = Nothing::class)
