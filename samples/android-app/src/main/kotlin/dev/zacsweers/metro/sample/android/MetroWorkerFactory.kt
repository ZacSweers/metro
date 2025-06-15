// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.reflect.KClass

@ContributesBinding(AppScope::class)
@Inject
class MetroWorkerFactory(
  val workerProviders: Map<KClass<out ListenableWorker>, WorkerInstanceFactory<*>>
) : WorkerFactory() {
  override fun createWorker(
    appContext: Context,
    workerClassName: String,
    workerParameters: WorkerParameters,
  ): ListenableWorker? {
    return workerProviders[Class.forName(workerClassName).kotlin]?.create(workerParameters)
  }

  interface WorkerInstanceFactory<T : ListenableWorker> {
    fun create(params: WorkerParameters): T
  }
}
