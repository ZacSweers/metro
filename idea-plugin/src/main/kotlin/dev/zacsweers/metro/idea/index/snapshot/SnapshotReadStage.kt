// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Retries one capture stage after a write action. Only a completed result crosses the read
 * boundary; callers retain earlier stages and reject the whole attempt when its inputs change.
 */
internal suspend fun <T> readSnapshotStage(
  project: Project,
  checkCurrent: () -> Unit,
  capture: () -> T,
): T {
  currentCoroutineContext().ensureActive()
  checkCurrent()
  val result =
    smartReadAction(project) {
      checkCurrent()
      ProgressManager.checkCanceled()
      val captured = capture()
      ProgressManager.checkCanceled()
      checkCurrent()
      captured
    }
  currentCoroutineContext().ensureActive()
  checkCurrent()
  return result
}
