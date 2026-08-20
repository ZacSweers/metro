// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.zacsweers.metro.idea.model.GraphContext

/** Exports only the exact graph path selected in the Metro tree. */
internal class ExportGraphDebugInfoAction(
  private val project: Project,
  private val selectedContext: () -> GraphContext?,
) :
  AnAction(
    "Export Graph Debug Info",
    "Write a local report without source bodies, absolute paths, or annotation literal values",
    null,
  ),
  DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = selectedContext() != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val context = selectedContext() ?: return
    project.service<MetroGraphDebugExporter>().export(context)
  }
}
