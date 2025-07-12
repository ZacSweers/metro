// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import androidx.work.ListenableWorker
import dev.zacsweers.metro.MapKey
import kotlin.reflect.KClass

/** A [MapKey] annotation for binding Worker in a multibinding map. */
@MapKey
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class WorkerKey(val value: KClass<out ListenableWorker>)
