// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.PopupHandler
import dev.zacsweers.metro.idea.index.MetroResolutionService
import javax.swing.JComponent

/** The browser's single explicit refresh control, including its initial load. */
internal class RefreshGraphsAction(
  private val resolutionService: MetroResolutionService,
  private val tracedRefreshAction: AnAction? = null,
  private val onRefresh: () -> Unit,
) :
  AnAction("Refresh", "Refresh graphs and bindings", AllIcons.Actions.Refresh),
  DumbAware,
  CustomComponentAction {
  init {
    templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
  }

  /** Uses the standard toolbar button and attaches the optional debugging context menu. */
  override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
    val button =
      ActionButtonWithText(this, presentation, place, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE)
    tracedRefreshAction?.let { action ->
      PopupHandler.installPopupMenu(button, DefaultActionGroup(action), "MetroRefreshPopup")
    }
    return button
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val explicitRefreshPending = resolutionService.isExplicitGraphRefreshPending
    val refreshing = explicitRefreshPending || resolutionService.indexBuildProgress.value != null
    val icon = if (refreshing) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
    e.presentation.icon = icon
    // ActionButton preserves an explicit disabled icon, so the spinner continues while clicks stop.
    e.presentation.disabledIcon = if (refreshing) icon else null
    e.presentation.isEnabled = !explicitRefreshPending
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
