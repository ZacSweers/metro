// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Application
import androidx.annotation.VisibleForTesting
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

class MetroApp : Application(), MetroApplication, Configuration.Provider {

  internal var appGraph: AppGraph = createGraphFactory<AppGraph.Factory>().create(this)

  override val appComponentProviders: MetroAppComponentProviders
    get() = appGraph

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(appGraph.workerFactory).build()

  override fun onCreate() {
    super.onCreate()
    scheduleBackgroundWork()
  }

  @VisibleForTesting
  fun setTestGraph(graph: AppGraph) {
    appGraph = graph
  }

  private fun scheduleBackgroundWork() {
    val workRequest =
      OneTimeWorkRequestBuilder<SampleWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(Data.Builder().putString("workName", "onCreate").build())
        .build()

    appGraph.workManager.enqueue(workRequest)

    val secondWorkRequest =
      OneTimeWorkRequestBuilder<SecondWorker>()
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .setInputData(Data.Builder().putString("workName", "onCreate").build())
        .build()

    appGraph.workManager.enqueue(secondWorkRequest)
  }
}
