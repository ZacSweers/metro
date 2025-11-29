// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.viewmodel.ViewModelGraph
import kotlin.reflect.KClass

@DependencyGraph(AppScope::class)
interface AppGraph : MetroAppComponentProviders, ViewModelGraph {

  @Provides fun provideApplicationContext(application: Application): Context = application

  val workManager: WorkManager

  @Provides
  fun providesWorkManager(application: Context): WorkManager {
    return WorkManager.getInstance(application)
  }

  @Multibinds
  val workerProviders:
    Map<KClass<out ListenableWorker>, Provider<MetroWorkerFactory.WorkerInstanceFactory<*>>>

  val workerFactory: MetroWorkerFactory

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides application: Application): AppGraph
  }
}
