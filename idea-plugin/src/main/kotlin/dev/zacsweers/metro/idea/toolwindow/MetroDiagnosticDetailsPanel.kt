// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.net.URI
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Displays one row's captured diagnostic text and performs explicit copy or documentation actions.
 */
internal class MetroDiagnosticDetailsPanel(
  private val copyText: (String) -> Unit = {
    CopyPasteManager.getInstance().setContents(StringSelection(it))
  },
  private val openDocumentation: (String) -> Unit = { BrowserUtil.browse(URI.create(it)) },
) : JPanel(BorderLayout()) {
  private var diagnostic: MetroTreeNode.Diagnostic? = null
  internal val textArea =
    JTextArea().apply {
      isEditable = false
      font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
      margin = JBUI.insets(8)
      accessibleContext.accessibleName = "Diagnostic details"
    }

  internal val copyAction: AnAction =
    object :
      AnAction("Copy Diagnostic", "Copy the complete Metro diagnostic", AllIcons.Actions.Copy),
      DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = diagnostic != null
      }

      override fun actionPerformed(e: AnActionEvent) {
        val current = diagnostic ?: return
        copyText(current.details)
      }
    }

  internal val documentationAction: AnAction =
    object :
      AnAction(
        "Open Documentation",
        "Open documentation for this Metro diagnostic",
        AllIcons.Actions.Help,
      ),
      DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = diagnostic?.documentationUrl != null
      }

      override fun actionPerformed(e: AnActionEvent) {
        val url = diagnostic?.documentationUrl ?: return
        openDocumentation(url)
      }
    }

  init {
    isVisible = false
    val toolbar =
      ActionManager.getInstance()
        .createActionToolbar(
          "MetroDiagnosticDetails",
          DefaultActionGroup(copyAction, documentationAction),
          true,
        )
    toolbar.targetComponent = textArea
    val header =
      JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyLeft(8)
        add(JBLabel("Diagnostic details"), BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
      }
    add(header, BorderLayout.NORTH)
    add(JBScrollPane(textArea), BorderLayout.CENTER)
  }

  /** Selection changes consume precomputed text, keeping rendering and PSI access off the EDT. */
  fun showDiagnostic(diagnostic: MetroTreeNode.Diagnostic?) {
    if (this.diagnostic === diagnostic) return
    this.diagnostic = diagnostic
    textArea.text = diagnostic?.details.orEmpty()
    textArea.caretPosition = 0
    isVisible = diagnostic != null
  }
}
