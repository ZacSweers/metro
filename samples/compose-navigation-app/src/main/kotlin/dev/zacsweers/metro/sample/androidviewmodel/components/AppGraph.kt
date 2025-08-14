// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.androidviewmodel.components

import android.app.Activity
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.ViewModelGraph
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph.Factory {
  /**
   * A multibinding map of activity classes to their providers accessible for
   * [MetroAppComponentFactory].
   */
  @Multibinds val activityProviders: Map<KClass<out Activity>, Provider<Activity>>

  @Provides @SingleIn(AppScope::class) fun provideViewModelCounter(): AtomicInt = AtomicInt(0)
}
