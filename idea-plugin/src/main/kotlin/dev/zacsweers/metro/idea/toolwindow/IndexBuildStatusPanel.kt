// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar

/** Distinguishes retained, queued, and actively rebuilding graph data without hiding the tree. */
internal class IndexBuildStatusPanel(onRefresh: () -> Unit) :
  JPanel(BorderLayout(0, JBUI.scale(4))) {
  internal val messageLabel = JBLabel()
  internal val retainedDataLabel =
    JBLabel("Showing previous graph data").apply {
      foreground = UIUtil.getContextHelpForeground()
      isVisible = false
    }
  internal val progressBar = JProgressBar()
  internal val refreshLink = ActionLink("Refresh") { onRefresh() }

  init {
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(6, 8)
    progressBar.isStringPainted = false
    val header =
      JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        add(messageLabel, BorderLayout.CENTER)
        add(refreshLink, BorderLayout.EAST)
      }
    add(header, BorderLayout.NORTH)
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
    refreshLink.isVisible = false
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
    showIdle("Metro graphs have not been loaded", actionText = "Load")
  }

  fun showRefreshRequired() {
    showIdle("Metro graph data may be stale")
  }

  private fun showIdle(
    message: String,
    showingPreviousData: Boolean = false,
    actionText: String = "Refresh",
  ) {
    messageLabel.text = message
    retainedDataLabel.isVisible = showingPreviousData
    refreshLink.text = actionText
    refreshLink.isVisible = true
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
    isVisible = true
  }

  fun clear() {
    isVisible = false
    retainedDataLabel.isVisible = false
    refreshLink.isVisible = false
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
  }
}
