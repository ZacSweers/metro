// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.ui.JBSplitter
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.MetroNavigationService
import dev.zacsweers.metro.idea.toolwindow.MetroTreeNavigation
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel
import kotlinx.coroutines.Job

/** Displays a captured selection and owns candidate navigation until its dialog closes. */
internal class MetroBindingExplanationPanel(
  private val project: Project,
  private val explanation: MetroBindingExplanation,
  private val copyText: (String) -> Unit = {
    CopyPasteManager.getInstance().setContents(StringSelection(it))
  },
) : JPanel(BorderLayout()), Disposable {
  private var disposed = false
  internal val tree =
    Tree(
        DefaultTreeModel(
          DefaultMutableTreeNode().apply {
            for (candidate in explanation.candidates) add(DefaultMutableTreeNode(candidate))
          }
        )
      )
      .apply {
        isRootVisible = false
        showsRootHandles = false
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = DefaultTreeCellRenderer().apply { leafIcon = MetroIcons.PROVIDER }
        accessibleContext.accessibleName = "Metro binding candidates"
        if (explanation.candidates.isNotEmpty()) setSelectionRow(0)
      }
  internal val summaryArea =
    textArea("Binding selection summary").apply {
      text = explanation.summary
      rows = 5
    }
  internal val detailArea = textArea("Binding candidate details")
  internal val treeNavigation =
    MetroTreeNavigation(
      project,
      tree,
      this,
      canNavigate = { !disposed && selectedCandidate() != null },
      resolveAndNavigate = ::resolveSelected,
    )
  internal val copyAction: AnAction =
    object :
      AnAction(
        "Copy Explanation",
        "Copy the request, graph context, and every candidate decision",
        AllIcons.Actions.Copy,
      ),
      DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = !disposed
      }

      override fun actionPerformed(e: AnActionEvent) {
        if (!disposed) copyText(explanation.copyText)
      }
    }

  init {
    preferredSize = Dimension(800, 480)
    TreeSpeedSearch.installOn(tree)
    tree.addTreeSelectionListener { showSelectedCandidate() }
    treeNavigation.openSourceAction.templatePresentation.icon = MetroIcons.PROVIDER
    val toolbar =
      ActionManager.getInstance()
        .createActionToolbar(
          "MetroBindingExplanation",
          DefaultActionGroup(
            copyAction,
            treeNavigation.openSourceAction,
            treeNavigation.autoscrollAction,
          ),
          true,
        )
    toolbar.targetComponent = tree
    val header =
      JPanel(BorderLayout()).apply {
        add(toolbar.component, BorderLayout.NORTH)
        add(JBScrollPane(summaryArea), BorderLayout.CENTER)
      }
    val candidatesAndDetails =
      JBSplitter(false, 0.45f).apply {
        firstComponent = JBScrollPane(tree)
        secondComponent = JBScrollPane(detailArea)
      }
    val snapshotLabel =
      JBLabel("Snapshot of graph data. Run the action again after code changes.").apply {
        border = JBUI.Borders.empty(6, 8)
      }
    add(header, BorderLayout.NORTH)
    add(candidatesAndDetails, BorderLayout.CENTER)
    add(snapshotLabel, BorderLayout.SOUTH)
    showSelectedCandidate()
  }

  /** The candidate tree contains captured values, so selection does not resolve PSI. */
  private fun selectedCandidate(): MetroBindingCandidate? {
    val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
    return node.userObject as? MetroBindingCandidate
  }

  private fun showSelectedCandidate() {
    detailArea.text = selectedCandidate()?.details ?: "No candidates for this dependency."
    detailArea.caretPosition = 0
  }

  private fun resolveSelected(requestFocus: Boolean): Job? {
    val candidate = selectedCandidate() ?: return null
    return project.service<MetroNavigationService>().resolveTargets(
      this,
      listOf(candidate.target.pointer),
    ) { targets ->
      if (disposed) return@resolveTargets
      val target = targets.singleOrNull() as? Navigatable ?: return@resolveTargets
      if (target.canNavigate()) target.navigate(requestFocus)
    }
  }

  override fun dispose() {
    disposed = true
    treeNavigation.cancelPendingNavigation()
    tree.clearSelection()
  }

  private companion object {
    fun textArea(name: String): JTextArea =
      JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        margin = JBUI.insets(8)
        accessibleContext.accessibleName = name
      }
  }
}
