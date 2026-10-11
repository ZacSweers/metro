// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Service
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for binding a Service in a multibinding map.
 *
 * @property value The Service class used as the key. Defaults to the annotated class when omitted.
 */
@MapKey(implicitClassKey = true)
@Target(AnnotationTarget.CLASS)
public annotation class ServiceKey(val value: KClass<out Service> = Nothing::class)
