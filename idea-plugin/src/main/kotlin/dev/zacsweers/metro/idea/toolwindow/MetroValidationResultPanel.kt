// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.AbstractTreeStructure
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import dev.zacsweers.metro.idea.MetroNavigationService
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.presentableName
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.tree.TreePath
import kotlinx.coroutines.Job

/** The latest requested run, retained independently of graph-browser refreshes and filters. */
internal class MetroValidationResultPanel(
  private val project: Project,
  private val onClose: () -> Unit,
) : JPanel(BorderLayout()), Disposable {
  private val structure = MetroValidationResultTreeStructure(project)
  private val model = StructureTreeModel(structure, this)
  internal val tree =
    Tree(AsyncTreeModel(model, this)).apply {
      isRootVisible = false
      showsRootHandles = true
    }
  private var generation = 0L
  private var disposed = false
  internal val closeAction: AnAction =
    object :
      AnAction("Close results", "Close the last validation result", AllIcons.Actions.Close),
      DumbAware {
      override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

      override fun actionPerformed(e: AnActionEvent) {
        onClose()
      }
    }

  init {
    isVisible = false
    TreeSpeedSearch.installOn(tree)
    object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean = navigateSelected() != null
      }
      .installOn(tree)
    val toolbar =
      ActionManager.getInstance()
        .createActionToolbar("MetroValidationResults", DefaultActionGroup(closeAction), true)
    toolbar.targetComponent = tree
    val header =
      JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyLeft(8)
        add(JBLabel("Last validation"), BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
      }
    add(header, BorderLayout.NORTH)
    add(JBScrollPane(tree), BorderLayout.CENTER)
  }

  /** Accepts completed callback data; row rendering stays on the tree model's background read. */
  fun showResults(results: List<KaGraphValidationResult>) {
    if (disposed || project.isDisposed) return
    val currentGeneration = ++generation
    structure.showResults(results)
    isVisible = true
    val firstProblem = results.firstOrNull { result ->
      result !is KaGraphValidationResult.Completed || result.diagnostics.isNotEmpty()
    }
    val selectedPath = (firstProblem ?: results.firstOrNull())?.context?.path
    model.invalidateAsync().thenRun {
      SwingUtilities.invokeLater {
        if (disposed || project.isDisposed || generation != currentGeneration) return@invokeLater
        // Expand one result to its summary and diagnostic rows. Stacks remain collapsed.
        TreeUtil.promiseSelect(tree, resultVisitor(selectedPath))
      }
    }
  }

  /** Releases the previous run when the pane closes or another explicit request starts. */
  fun clear() {
    if (disposed) return
    generation++
    structure.clear()
    isVisible = false
    model.invalidateAsync()
  }

  private fun resultVisitor(selectedPath: GraphPath?): TreeVisitor {
    return TreeVisitor { path ->
      when (val node = nodeAt(path)) {
        is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Graph ->
          if (node.context.path == selectedPath) TreeVisitor.Action.CONTINUE
          else TreeVisitor.Action.SKIP_CHILDREN
        is MetroTreeNode.Validation -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Summary -> TreeVisitor.Action.INTERRUPT
        else -> TreeVisitor.Action.SKIP_CHILDREN
      }
    }
  }

  private fun nodeAt(path: TreePath): MetroTreeNode? {
    return TreeUtil.getLastUserObject(MetroTreeNode::class.java, path)
      ?: TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element as? MetroTreeNode
  }

  /** Resolves the selected row while this result snapshot remains visible. */
  internal fun navigateSelected(): Job? {
    if (disposed || !isVisible) return null
    val pointer = tree.selectionPath?.let(::nodeAt)?.pointer ?: return null
    val currentGeneration = generation
    return project.service<MetroNavigationService>().resolveTargets(this, listOf(pointer)) { targets
      ->
      val isCurrentResult = isVisible && generation == currentGeneration
      if (disposed || !isCurrentResult) return@resolveTargets
      val target = targets.singleOrNull() as? Navigatable ?: return@resolveTargets
      if (target.canNavigate()) target.navigate(true)
    }
  }

  override fun dispose() {
    disposed = true
    generation++
    structure.clear()
  }
}

/** Builds result rows from one immutable run snapshot without querying the browser's index. */
internal class MetroValidationResultTreeStructure(private val project: Project) :
  AbstractTreeStructure() {
  private val root = MetroTreeNode.Root()
  @Volatile private var resultsByPath: Map<GraphPath, KaGraphValidationResult>? = null

  /** Publishes one snapshot for subsequent background children requests. */
  fun showResults(results: List<KaGraphValidationResult>) {
    resultsByPath = results.associateBy { it.context.path }
  }

  /** Drops result ownership when the view closes or its owner is disposed. */
  fun clear() {
    resultsByPath = null
  }

  override fun getRootElement(): Any = root

  override fun getChildElements(element: Any): Array<Any> {
    val results = resultsByPath ?: return emptyArray()
    val children =
      when (element) {
        is MetroTreeNode.Root -> {
          if (results.isEmpty()) {
            listOf(MetroTreeNode.Summary(root, "No graph contexts were available for validation"))
          } else {
            results.values
              .map { result ->
                ProgressManager.checkCanceled()
                MetroTreeNode.Graph(
                  root,
                  result.context,
                  result.context.presentableName(includeFile = true),
                  validationSummary(result),
                )
              }
              .sortedWith(compareBy({ it.context.path.segments.size }, { it.text }))
          }
        }
        is MetroTreeNode.Graph -> {
          val result = results[element.context.path] ?: return emptyArray()
          listOf(MetroTreeNode.Validation(element, result, stale = false))
        }
        is MetroTreeNode.Validation -> validationTreeChildren(element)
        is MetroTreeNode.Diagnostic -> diagnosticTreeChildren(element)
        else -> emptyList()
      }
    return children.toTypedArray()
  }

  override fun getParentElement(element: Any): Any? = (element as? MetroTreeNode)?.parent

  override fun createDescriptor(
    element: Any,
    parentDescriptor: NodeDescriptor<*>?,
  ): NodeDescriptor<*> = MetroNodeDescriptor(project, parentDescriptor, element as MetroTreeNode)

  override fun commit() {}

  override fun hasSomethingToCommit(): Boolean = false

  override fun isAlwaysLeaf(element: Any): Boolean =
    element is MetroTreeNode.BindingRow ||
      element is MetroTreeNode.StackEntry ||
      element is MetroTreeNode.Summary
}
