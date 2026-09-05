// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.zacsweers.metro.idea.index.IndexBuildFile
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BoxLayout
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
  internal val workerFilesPanel =
    JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      isOpaque = false
      isVisible = false
    }
  private val workerRows = mutableListOf<WorkerFileRow>()

  init {
    isOpaque = false
    isVisible = false
    border = JBUI.Borders.empty(6, 8)
    progressBar.isStringPainted = false
    val heading =
      JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        messageLabel.alignmentX = LEFT_ALIGNMENT
        retainedDataLabel.alignmentX = LEFT_ALIGNMENT
        add(messageLabel)
        add(retainedDataLabel)
      }
    add(heading, BorderLayout.NORTH)
    add(progressBar, BorderLayout.CENTER)
    add(workerFilesPanel, BorderLayout.SOUTH)
  }

  fun show(progress: IndexBuildProgress, showingPreviousData: Boolean = false) {
    if (progress.phase == IndexBuildPhase.QUEUED) {
      showRefreshQueued(showingPreviousData)
      return
    }
    messageLabel.text = progress.message
    retainedDataLabel.isVisible = showingPreviousData
    showWorkerFiles(progress.workerFiles)
    progressBar.isVisible = true
    val total = progress.total
    if (total != null && total > 0 && !progress.phase.discoversMoreWork) {
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
      if (showingPreviousData) {
        "Metro graph data may be stale. Refresh is queued"
      } else {
        "Metro graph refresh is queued"
      }
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
    showWorkerFiles(emptyList())
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
    isVisible = true
  }

  fun clear() {
    isVisible = false
    retainedDataLabel.isVisible = false
    showWorkerFiles(emptyList())
    progressBar.isVisible = false
    progressBar.isIndeterminate = false
  }

  /** Stable slots keep file changes in place while workers finish at different rates. */
  private fun showWorkerFiles(files: List<IndexBuildFile?>) {
    if (workerRows.size != files.size) {
      workerRows.clear()
      workerFilesPanel.removeAll()
      repeat(files.size) { slot ->
        val row = WorkerFileRow(slot + 1)
        workerRows += row
        workerFilesPanel.add(row)
      }
      workerFilesPanel.revalidate()
    }
    files.forEachIndexed { slot, file -> workerRows[slot].show(file) }
    workerFilesPanel.isVisible = files.isNotEmpty()
    workerFilesPanel.repaint()
  }

  /** Clips long locations within the tool window and keeps the full location in a tooltip. */
  internal class WorkerFileRow(private val workerNumber: Int) :
    JPanel(BorderLayout(JBUI.scale(8), 0)) {
    internal val workerLabel =
      JBLabel(workerNumber.toString()).apply {
        foreground = UIUtil.getContextHelpForeground()
      }
    internal val fileLabel = SimpleColoredComponent().apply { isOpaque = false }

    init {
      isOpaque = false
      alignmentX = LEFT_ALIGNMENT
      border = JBUI.Borders.empty(2, 0)
      add(workerLabel, BorderLayout.WEST)
      add(fileLabel, BorderLayout.CENTER)
    }

    fun show(file: IndexBuildFile?) {
      fileLabel.clear()
      fileLabel.icon = AllIcons.FileTypes.Text
      val description =
        if (file == null) {
          fileLabel.append("Idle", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          "Worker $workerNumber: Idle"
        } else {
          fileLabel.append(file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
          val locationPath =
            if (file.name == file.path.substringAfterLast('/')) {
              file.path.substringBeforeLast('/', "")
            } else {
              file.path
            }
          val location =
            listOfNotNull(file.module, locationPath.takeIf { it.isNotEmpty() }).joinToString(" · ")
          if (location.isNotEmpty()) {
            fileLabel.append("  $location", SimpleTextAttributes.GRAYED_ATTRIBUTES)
          }
          val fullLocation = listOfNotNull(file.module, file.path).joinToString(" · ")
          if (file.name == file.path.substringAfterLast('/')) {
            "Worker $workerNumber: $fullLocation"
          } else {
            "Worker $workerNumber: ${file.name} · $fullLocation"
          }
        }
      toolTipText = description
      workerLabel.toolTipText = description
      fileLabel.toolTipText = description
      // Swing initializes accessibility lazily through the getter.
      getAccessibleContext().accessibleName = description
    }

    override fun getMinimumSize(): Dimension = Dimension(0, preferredSize.height)

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
  }
}
