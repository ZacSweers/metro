// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.zacsweers.metro.idea.graph.GraphValidationProgress
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

internal class ValidationStatusPanel : JPanel(BorderLayout(0, JBUI.scale(4))) {
  internal val messageLabel = JBLabel()
  internal val progressBar = JProgressBar()

  init {
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(6, 8)
    progressBar.isStringPainted = false
    add(messageLabel, BorderLayout.NORTH)
    add(progressBar, BorderLayout.SOUTH)
  }

  fun show(progress: GraphValidationProgress) {
    messageLabel.text = progress.message
    progressBar.isVisible = true
    val completed = progress.completed
    val total = progress.total
    if (completed != null && total != null) {
      progressBar.isIndeterminate = false
      progressBar.minimum = 0
      progressBar.maximum = total
      progressBar.value = completed
    } else {
      progressBar.isIndeterminate = true
    }
    isVisible = true
  }

  fun clear() {
    isVisible = false
    progressBar.isIndeterminate = false
  }
}
