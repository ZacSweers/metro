// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.content.ContentProvider
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for binding [ContentProviders][ContentProvider] into a multibinding map.
 *
 * @property value The provider class used as the key. Defaults to the annotated class when omitted.
 */
@MapKey(implicitClassKey = true)
@Target(AnnotationTarget.CLASS)
public annotation class ContentProviderKey(val value: KClass<out ContentProvider> = Nothing::class)
