// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import javax.swing.Action
import javax.swing.JComponent

/** Keeps one explanation open while candidate navigation returns focus to the editor. */
internal class MetroBindingExplanationDialog(
  project: Project,
  explanation: MetroBindingExplanation,
) : DialogWrapper(project, false) {
  private val panel = MetroBindingExplanationPanel(project, explanation)

  init {
    title = "Why this Metro binding?"
    isModal = false
    isResizable = true
    setCancelButtonText("Close")
    Disposer.register(disposable, panel)
    init()
  }

  override fun createCenterPanel(): JComponent = panel

  override fun createActions(): Array<Action> = arrayOf(cancelAction)

  override fun getPreferredFocusedComponent(): JComponent = panel.tree

  override fun getDimensionServiceKey(): String = "dev.zacsweers.metro.idea.bindingExplanation"
}
