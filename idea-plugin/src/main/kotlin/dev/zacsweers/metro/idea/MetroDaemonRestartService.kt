// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether a requested highlighting restart can run without interrupting the current pass.
 */
internal class DaemonRestartState<T : Any> {
  private val activeDaemonOwners = IdentityHashMap<T, Unit>()
  private var restartPending = false

  @get:Synchronized
  val hasPendingRestart: Boolean
    get() = restartPending

  @Synchronized
  fun daemonStarted(owners: Collection<T>) {
    owners.forEach { owner -> activeDaemonOwners[owner] = Unit }
  }

  @Synchronized
  fun daemonStopped(owners: Collection<T>) {
    owners.forEach(activeDaemonOwners::remove)
  }

  @Synchronized
  fun requestRestart() {
    restartPending = true
  }

  @Synchronized
  fun takePendingRestart(): Boolean {
    if (activeDaemonOwners.isNotEmpty() || !restartPending) {
      return false
    }
    restartPending = false
    return true
  }

  @Synchronized
  fun clear() {
    restartPending = false
  }
}

/** Tracks daemon activity before Metro's project service is first requested. */
internal class MetroDaemonListener(private val project: Project) :
  DaemonCodeAnalyzer.DaemonListener {
  override fun daemonStarting(fileEditors: Collection<FileEditor>) {
    project.service<MetroDaemonRestartService>().daemonStarted(fileEditors)
  }

  override fun daemonFinished(fileEditors: Collection<FileEditor>) {
    project.service<MetroDaemonRestartService>().daemonStopped(fileEditors)
  }

  override fun daemonCanceled(reason: String, fileEditors: Collection<FileEditor>) {
    project.service<MetroDaemonRestartService>().daemonStopped(fileEditors)
  }
}

/** Batches Metro highlighting refreshes so the current daemon pass can finish first. */
@Service(Service.Level.PROJECT)
internal class MetroDaemonRestartService(private val project: Project) : Disposable {
  private val restartState = DaemonRestartState<FileEditor>()
  private val restartCheckScheduled = AtomicBoolean()
  private val disposed = AtomicBoolean()

  fun daemonStarted(fileEditors: Collection<FileEditor>) {
    restartState.daemonStarted(fileEditors)
  }

  fun daemonStopped(fileEditors: Collection<FileEditor>) {
    restartState.daemonStopped(fileEditors)
    schedulePendingRestart()
  }

  fun requestRestart(inUnitTests: Boolean = false) {
    if (
      (ApplicationManager.getApplication().isUnitTestMode && !inUnitTests) ||
        disposed.get() ||
        project.isDisposed
    ) {
      return
    }
    restartState.requestRestart()
    schedulePendingRestart()
  }

  private fun schedulePendingRestart() {
    if (!restartState.hasPendingRestart || disposed.get() || project.isDisposed) return
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
    if (disposed.get() || project.isDisposed) return
    if (restartState.takePendingRestart()) {
      DaemonCodeAnalyzer.getInstance(project).restart("Metro IDE state changed")
    }
  }

  override fun dispose() {
    disposed.set(true)
    restartState.clear()
    restartCheckScheduled.set(false)
  }
}
