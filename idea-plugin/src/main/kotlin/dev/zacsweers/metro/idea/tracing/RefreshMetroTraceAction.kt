// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.zacsweers.metro.idea.index.MetroResolutionService

/** Starts recording before one refresh and saves after its generation and admitted work finish. */
internal class RefreshMetroTraceAction(private val targetProject: Project? = null) :
  AnAction(
    "Refresh with tracing",
    "Trace a Metro refresh through index publication and save a local Perfetto file",
    null,
  ),
  DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project)
    e.presentation.isVisible = project != null
    if (project == null) {
      e.presentation.isEnabled = false
      return
    }
    val captureIdle = project.service<MetroIdeTracingService>().state.value == IdeTraceState.IDLE
    val refreshPending = project.service<MetroResolutionService>().isExplicitGraphRefreshPending
    e.presentation.isEnabled = captureIdle && !refreshPending
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project) ?: return
    project.service<MetroIdeTracingService>().refreshWithTracing()
  }
}
