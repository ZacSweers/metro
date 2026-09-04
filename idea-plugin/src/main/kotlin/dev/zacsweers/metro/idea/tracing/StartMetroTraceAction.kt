// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.zacsweers.metro.idea.MetroSettings

/** Starts a local capture after the project explicitly enables debugging controls. */
internal class StartMetroTraceAction(private val targetProject: Project? = null) :
  AnAction(
    "Start Metro Performance Trace",
    "Record Metro IDE work to a local Perfetto trace",
    null,
  ),
  DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project)
    e.presentation.isVisible = project != null
    e.presentation.isEnabled =
      project != null && project.service<MetroIdeTracingService>().state.value == IdeTraceState.IDLE
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project) ?: return
    project.service<MetroIdeTracingService>().startCapture()
  }
}

/** Rechecks settings at invocation so stale action presentations cannot start a capture. */
internal fun metroTraceActionProject(project: Project?): Project? {
  if (project == null || project.isDisposed) return null
  if (!MetroSettings.getInstance(project).state.enableDebuggingOptions) return null
  return project
}
