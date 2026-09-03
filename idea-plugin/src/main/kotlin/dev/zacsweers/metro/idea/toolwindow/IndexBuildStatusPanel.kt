// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Keeps the previous graph data's status visible while a background refresh is running. */
internal class IndexBuildStatusPanel : JPanel(BorderLayout(0, JBUI.scale(4))) {
  internal val messageLabel = JBLabel()
  internal val retainedDataLabel =
    JBLabel("Showing previous graph data").apply {
      foreground = UIUtil.getContextHelpForeground()
      isVisible = false
    }
  internal val progressBar = JProgressBar()

  init {
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(6, 8)
    progressBar.isStringPainted = false
    add(messageLabel, BorderLayout.NORTH)
    add(retainedDataLabel, BorderLayout.CENTER)
    add(progressBar, BorderLayout.SOUTH)
  }

  fun show(progress: IndexBuildProgress, showingPreviousData: Boolean = false) {
    messageLabel.text = progress.message
    retainedDataLabel.isVisible = showingPreviousData
    progressBar.isVisible = true
    val total = progress.total
    if (total != null && total > 0) {
      progressBar.isIndeterminate = false
      progressBar.minimum = 0
      progressBar.maximum = total
      progressBar.value = progress.completed?.coerceAtMost(total) ?: 0
    } else {
      progressBar.isIndeterminate = true
    }
    isVisible = true
  }

  fun showWaitingForIdeIndexing(showingPreviousData: Boolean = false) {
    messageLabel.text = "Waiting for IDE indexing to finish"
    retainedDataLabel.isVisible = showingPreviousData
    progressBar.isVisible = true
    progressBar.isIndeterminate = true
    isVisible = true
  }

  fun showNotLoaded() {
    messageLabel.text = "Metro graphs have not been loaded"
    retainedDataLabel.isVisible = false
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
    isVisible = true
  }

  fun showRefreshRequired() {
    messageLabel.text = "Metro graph data may be stale. Click Refresh to update"
    retainedDataLabel.isVisible = false
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
    isVisible = true
  }

  fun clear() {
    isVisible = false
    retainedDataLabel.isVisible = false
    progressBar.isIndeterminate = false
  }
}
