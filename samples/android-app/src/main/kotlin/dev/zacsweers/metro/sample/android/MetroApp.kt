// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.app.Application
import androidx.tracing.AbstractTraceDriver
import androidx.tracing.DelicateTracingApi
import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

class MetroApp :
  Application(), MetroApplication, Configuration.Provider, AbstractTraceDriver.Factory {

  // The TraceSink
  internal val sink = TraceSink(context = this)

  // The TraceDriver
  internal val driver = TraceDriver(context = this, sink = sink, isCategoryEnabled = { true })

  @OptIn(DelicateTracingApi::class)
  private val appGraph by lazy {
    Tracer.setGlobalTracer(driver.tracer)
    createGraphFactory<AppGraph.Factory>().create(this, driver.tracer)
  }

  override val appComponentProviders: MetroAppComponentProviders
    get() = appGraph

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

  override fun create(): AbstractTraceDriver {
    // This ensures that the rest of the application can discover the right TraceDriver instance
    // to do things like flush traces for e.g. Especially relevant when using broadcasts to flush
    // traces.
    return driver
  }
}
