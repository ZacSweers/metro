// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Inject
class SampleWorker(context: Context, @Assisted params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    println("sample doWork running " + this.inputData.getString("workName"))
    delay(1.seconds)
    return Result.success()
  }

  @WorkerKey(SampleWorker::class)
  @ContributesIntoMap(
    AppScope::class,
    binding = binding<MetroWorkerFactory.WorkerInstanceFactory<*>>(),
  )
  @AssistedFactory
  abstract class Factory : MetroWorkerFactory.WorkerInstanceFactory<SampleWorker>
}
