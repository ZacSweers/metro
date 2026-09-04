// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import dev.zacsweers.metro.idea.index.MetroResolutionService

/** The browser's single explicit refresh control, including its initial load. */
internal class RefreshGraphsAction(
  private val resolutionService: MetroResolutionService,
  private val onRefresh: () -> Unit,
) : AnAction("Refresh", "Refresh graphs and bindings", AllIcons.Actions.Refresh), DumbAware {
  init {
    templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = !resolutionService.isExplicitGraphRefreshPending
  }

  override fun actionPerformed(e: AnActionEvent) {
    refresh()
  }

  /** Automatic work remains bypassable; an existing explicit request keeps its place. */
  fun refresh() {
    if (resolutionService.isExplicitGraphRefreshPending) return
    resolutionService.refreshGraphData()
    onRefresh()
  }
}
