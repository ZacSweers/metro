// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.app.Service
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding a Service in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
public annotation class ServiceKey(val value: KClass<out Service>)
