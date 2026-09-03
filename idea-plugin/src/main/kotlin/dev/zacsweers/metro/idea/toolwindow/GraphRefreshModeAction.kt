// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.applyMetroSettings
import javax.swing.JComponent

/** Exposes the persisted refresh policy beside the graph browser's explicit Refresh action. */
internal class GraphRefreshModeAction(
  private val project: Project,
  private val onChanged: () -> Unit,
) : ComboBoxAction(), DumbAware {
  init {
    templatePresentation.text = selectedLabel()
    templatePresentation.description = "Choose when Metro refreshes graphs and bindings"
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = selectedLabel()
    e.presentation.isEnabled = !project.isDisposed
  }

  public override fun createPopupActionGroup(
    button: JComponent,
    dataContext: DataContext,
  ): DefaultActionGroup =
    DefaultActionGroup(
      option("Manual", automatic = false),
      option("Automatic", automatic = true),
    )

  private fun selectedLabel(): String =
    if (MetroSettings.getInstance(project).state.automaticallyRefreshGraphData) "Automatic"
    else "Manual"

  private fun option(label: String, automatic: Boolean): ToggleAction =
    object : ToggleAction(label), DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun isSelected(e: AnActionEvent): Boolean =
        MetroSettings.getInstance(project).state.automaticallyRefreshGraphData == automatic

      override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state || project.isDisposed) return
        val settings = MetroSettings.getInstance(project).state
        if (settings.automaticallyRefreshGraphData == automatic) return
        settings.automaticallyRefreshGraphData = automatic
        applyMetroSettings(project)
        onChanged()
      }
    }
}
