// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.android.components

import android.app.Activity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph {
  /**
   * A multibinding map of activity classes to their providers accessible for
   * [dev.zacsweers.metro.sample.android.components.MetroAppComponentFactory].
   */
  @Multibinds val activityProviders: Map<KClass<out Activity>, Provider<Activity>>

  @Provides @SingleIn(AppScope::class) fun providesViewModelCounter(): AtomicInt = AtomicInt(0)
}
