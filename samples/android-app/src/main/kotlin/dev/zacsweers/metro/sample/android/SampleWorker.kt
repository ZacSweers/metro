// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

@Inject
class SampleWorker(context: Context, @Assisted params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    println("doWork running " + this.inputData.getString("workName"))
    delay(1.seconds)
    return Result.success()
  }

  @AssistedFactory abstract class Factory : MetroWorkerFactory.WorkerInstanceFactory<SampleWorker>
}
