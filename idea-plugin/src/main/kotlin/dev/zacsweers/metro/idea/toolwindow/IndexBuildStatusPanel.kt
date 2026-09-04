// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Distinguishes retained, queued, and actively rebuilding graph data without hiding the tree. */
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
    if (progress.phase == IndexBuildPhase.QUEUED) {
      showRefreshQueued(showingPreviousData)
      return
    }
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
    showIdle("Waiting for IDE indexing to finish", showingPreviousData)
  }

  fun showRefreshQueued(showingPreviousData: Boolean = false) {
    val message =
      if (showingPreviousData) "Metro graph data may be stale. Refresh is queued"
      else "Metro graph refresh is queued"
    showIdle(message, showingPreviousData)
  }

  fun showNotLoaded() {
    showIdle("Click Refresh to load Metro graphs")
  }

  fun showRefreshRequired() {
    showIdle("Metro graph data may be stale. Click Refresh to update")
  }

  private fun showIdle(
    message: String,
    showingPreviousData: Boolean = false,
  ) {
    messageLabel.text = message
    retainedDataLabel.isVisible = showingPreviousData
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
    isVisible = true
  }

  fun clear() {
    isVisible = false
    retainedDataLabel.isVisible = false
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
  }
}
