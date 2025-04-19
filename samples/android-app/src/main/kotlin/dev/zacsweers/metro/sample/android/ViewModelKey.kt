// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding ViewModels in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ViewModelKey(val value: KClass<out ViewModel>)
