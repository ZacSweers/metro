// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.content.ContentProvider
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for binding [ContentProviders][ContentProvider] into a multibinding map.
 */
@MapKey
@Target(AnnotationTarget.CLASS)
public annotation class ContentProviderKey(val value: KClass<out ContentProvider>)
