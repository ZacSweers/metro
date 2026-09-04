// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.readAttempt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Retries one capture stage after a write action. Only a completed result crosses the read
 * boundary; callers retain earlier stages and reject the whole attempt when its inputs change.
 * Active read time and attempt counts accumulate on the caller's phase across individual file
 * reads.
 */
internal suspend fun <T> readSnapshotStage(
  project: Project,
  checkCurrent: () -> Unit,
  trace: IdeTraceOperation? = null,
  capture: () -> T,
): T {
  currentCoroutineContext().ensureActive()
  checkCurrent()
  val result =
    smartReadAction(project) {
      trace.readAttempt {
        checkCurrent()
        ProgressManager.checkCanceled()
        val captured = capture()
        ProgressManager.checkCanceled()
        checkCurrent()
        captured
      }
    }
  currentCoroutineContext().ensureActive()
  checkCurrent()
  return result
}
