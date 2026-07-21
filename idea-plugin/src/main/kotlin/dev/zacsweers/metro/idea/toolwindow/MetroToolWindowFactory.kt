// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Creates the Metro tool window content. The panel owns its own lifecycle via the content. */
internal class MetroToolWindowFactory : ToolWindowFactory, DumbAware {

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val panel = MetroToolWindowPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, null, false)
    content.setDisposer(panel)
    toolWindow.contentManager.addContent(content)
  }
}
