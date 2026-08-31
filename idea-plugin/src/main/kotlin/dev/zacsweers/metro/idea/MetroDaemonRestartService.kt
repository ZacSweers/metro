// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/** Batches Metro highlighting refreshes so the current daemon pass can finish first. */
@Service(Service.Level.PROJECT)
internal class MetroDaemonRestartService(private val project: Project) : Disposable {
  private val restartPending = AtomicBoolean()
  private val restartCheckScheduled = AtomicBoolean()
  private val disposed = AtomicBoolean()

  init {
    project.messageBus
      .connect(this)
      .subscribe(
        DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
        object : DaemonCodeAnalyzer.DaemonListener {
          override fun daemonFinished() = schedulePendingRestart()

          override fun daemonCancelEventOccurred(reason: String) = schedulePendingRestart()
        },
      )
  }

  fun requestRestart(inUnitTests: Boolean = false) {
    if (
      (ApplicationManager.getApplication().isUnitTestMode && !inUnitTests) ||
        disposed.get() ||
        project.isDisposed
    ) {
      return
    }
    restartPending.set(true)
    schedulePendingRestart()
  }

  private fun schedulePendingRestart() {
    if (!restartPending.get() || disposed.get() || project.isDisposed) return
    val application = ApplicationManager.getApplication()
    if (!application.isDispatchThread) {
      if (!restartCheckScheduled.compareAndSet(false, true)) return
      application.invokeLater {
        restartCheckScheduled.set(false)
        if (!disposed.get() && !project.isDisposed) restartIfIdle()
      }
      return
    }
    restartIfIdle()
  }

  private fun restartIfIdle() {
    if (!restartPending.get() || disposed.get() || project.isDisposed) return
    val daemon = DaemonCodeAnalyzer.getInstance(project)
    if (daemon.isRunning) return
    if (restartPending.compareAndSet(true, false)) {
      daemon.restart("Metro IDE state changed")
    }
  }

  override fun dispose() {
    disposed.set(true)
    restartPending.set(false)
    restartCheckScheduled.set(false)
  }
}
