// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.content.ContentProvider
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding a ContentProvider in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
annotation class ProviderKey(val value: KClass<out ContentProvider>)
