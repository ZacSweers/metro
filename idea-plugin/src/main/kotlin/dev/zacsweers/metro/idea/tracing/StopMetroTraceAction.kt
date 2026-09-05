// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project

/** Finishes an active capture and lets the tracing service save work already in flight. */
internal class StopMetroTraceAction(private val targetProject: Project? = null) :
  AnAction(
    "Stop Metro Performance Trace",
    "Finish and save the current Metro performance trace",
    null,
  ),
  DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project)
    e.presentation.isVisible = project != null
    val state = project?.service<MetroIdeTracingService>()?.state?.value
    e.presentation.isEnabled = state == IdeTraceState.STARTING || state == IdeTraceState.RECORDING
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = metroTraceActionProject(targetProject ?: e.project) ?: return
    project.service<MetroIdeTracingService>().stopCapture()
  }
}
