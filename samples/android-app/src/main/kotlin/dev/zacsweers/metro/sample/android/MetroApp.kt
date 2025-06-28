// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Application
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import dev.zacsweers.metro.createGraphFactory

class MetroApp : Application(), Configuration.Provider {
  /** Holder reference for the app graph for [MetroAppComponentFactory]. */
  val appGraph by lazy { createGraphFactory<AppGraph.Factory>().create(this) }

  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().setWorkerFactory(appGraph.workerFactory).build()

  override fun onCreate() {
    super.onCreate()

    scheduleBackgroundWork()
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
