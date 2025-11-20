// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalAtomicApi::class)

package dev.zacsweers.metro.sample.androidviewmodel.components

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.sample.androidviewmodel.viewmodel.ViewModelGraph
import dev.zacsweers.metrox.android.MetroAndroidAppGraph
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph.Factory, MetroAndroidAppGraph {
  @Provides @SingleIn(AppScope::class) fun provideViewModelCounter(): AtomicInt = AtomicInt(0)
}
