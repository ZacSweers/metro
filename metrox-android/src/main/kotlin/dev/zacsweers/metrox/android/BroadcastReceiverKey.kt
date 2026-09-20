// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metrox.android

import android.content.BroadcastReceiver
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/**
 * A [MapKey] annotation for binding a BroadcastReceiver in a multibinding map.
 *
 * @property value The receiver class used as the key. Defaults to the annotated class when omitted.
 */
@MapKey(implicitClassKey = true)
@Target(AnnotationTarget.CLASS)
public annotation class BroadcastReceiverKey(
  val value: KClass<out BroadcastReceiver> = Nothing::class
)
