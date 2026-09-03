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
import com.intellij.ui.JBSplitter
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
import dev.zacsweers.metro.idea.graph.CachedValidation
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.presentableName
import java.awt.BorderLayout
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
  private val treeSelection = MetroTreeSelection(tree, this)
  internal val diagnosticDetails = MetroDiagnosticDetailsPanel()
  private val treeAndDetails = JBSplitter(true, 0.45f)
  private var generation = 0L
  private var disposed = false
  internal val treeNavigation =
    MetroTreeNavigation(
      project,
      tree,
      this,
      canNavigate = { selectedNavigationNode()?.pointer != null },
      resolveAndNavigate = ::resolveSelected,
    )
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
    tree.addTreeSelectionListener { showSelectedDiagnostic() }
    val toolbar =
      ActionManager.getInstance()
        .createActionToolbar(
          "MetroValidationResults",
          DefaultActionGroup(treeNavigation.autoscrollAction, closeAction),
          true,
        )
    toolbar.targetComponent = tree
    val header =
      JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyLeft(8)
        add(JBLabel("Last validation"), BorderLayout.CENTER)
        add(toolbar.component, BorderLayout.EAST)
      }
    add(header, BorderLayout.NORTH)
    treeAndDetails.firstComponent = JBScrollPane(tree)
    add(treeAndDetails, BorderLayout.CENTER)
  }

  /** Accepts completed callback data; row rendering stays on the tree model's background read. */
  fun showResults(results: List<KaGraphValidationResult>) {
    if (disposed || project.isDisposed) return
    val currentGeneration = ++generation
    clearDiagnosticSelection()
    structure.showResults(results)
    isVisible = true
    val firstProblem = results.firstOrNull { result ->
      result !is KaGraphValidationResult.Completed || result.diagnostics.isNotEmpty()
    }
    val selectedPath = (firstProblem ?: results.firstOrNull())?.context?.path
    val selectDiagnostic =
      firstProblem is KaGraphValidationResult.Completed && firstProblem.diagnostics.isNotEmpty()
    val selection = treeSelection.request {
      !project.isDisposed && isVisible && generation == currentGeneration
    }
    model.invalidateAsync().thenRun {
      SwingUtilities.invokeLater {
        if (disposed || project.isDisposed || generation != currentGeneration) return@invokeLater
        // Select the first problem so its detail is visible. Navigable stacks remain collapsed.
        selection.select(resultVisitor(selectedPath, selectDiagnostic))
      }
    }
  }

  /** Updates the displayed run's stale flags while retaining its selection and ownership. */
  fun refreshStaleness(retainedResults: List<CachedValidation>) {
    if (disposed || !isVisible) return
    val selected = tree.selectionPath?.let(::nodeAt)?.takeIf(structure::contains)
    val visitor = selected?.let { validationSelectionVisitor(it, ::nodeAt) }
    if (!structure.refreshStaleness(retainedResults)) return
    if (visitor == null) {
      model.invalidateAsync()
      return
    }
    val currentGeneration = generation
    val selection = treeSelection.request {
      !project.isDisposed && isVisible && generation == currentGeneration
    }
    model.invalidateAsync().thenRun {
      SwingUtilities.invokeLater { selection.select(visitor) }
    }
  }

  /** Releases the previous run when the pane closes or another explicit request starts. */
  fun clear() {
    if (disposed) return
    generation++
    treeSelection.cancelPendingSelection()
    structure.clear()
    clearDiagnosticSelection()
    isVisible = false
    model.invalidateAsync()
  }

  private fun resultVisitor(selectedPath: GraphPath?, selectDiagnostic: Boolean): TreeVisitor {
    return TreeVisitor { path ->
      when (val node = nodeAt(path)) {
        is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Graph ->
          if (node.context.path == selectedPath) TreeVisitor.Action.CONTINUE
          else TreeVisitor.Action.SKIP_CHILDREN
        is MetroTreeNode.Validation -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Summary ->
          if (selectDiagnostic) TreeVisitor.Action.SKIP_CHILDREN else TreeVisitor.Action.INTERRUPT
        is MetroTreeNode.Diagnostic ->
          if (selectDiagnostic) TreeVisitor.Action.INTERRUPT else TreeVisitor.Action.SKIP_CHILDREN
        else -> TreeVisitor.Action.SKIP_CHILDREN
      }
    }
  }

  private fun nodeAt(path: TreePath): MetroTreeNode? {
    return TreeUtil.getLastUserObject(MetroTreeNode::class.java, path)
      ?: TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element as? MetroTreeNode
  }

  /** Stack and related-binding rows keep their parent diagnostic visible while navigating. */
  private fun showSelectedDiagnostic() {
    var node = tree.selectionPath?.let(::nodeAt)
    while (node != null && node !is MetroTreeNode.Diagnostic) node = node.parent
    val diagnostic = (node as? MetroTreeNode.Diagnostic)?.takeIf { structure.contains(it) }
    diagnosticDetails.showDiagnostic(diagnostic)
    treeAndDetails.secondComponent = diagnosticDetails.takeIf { diagnostic != null }
  }

  private fun clearDiagnosticSelection() {
    treeNavigation.cancelPendingNavigation()
    tree.clearSelection()
    diagnosticDetails.showDiagnostic(null)
    treeAndDetails.secondComponent = null
  }

  /** Requests explicit navigation through the tree's keyboard and preview cancellation boundary. */
  internal fun navigateSelected(): Job? = treeNavigation.navigate(requestFocus = true)

  /** Resolves the selected row while this result snapshot remains visible. */
  private fun resolveSelected(requestFocus: Boolean): Job? {
    val pointer = selectedNavigationNode()?.pointer ?: return null
    val currentGeneration = generation
    return project.service<MetroNavigationService>().resolveTargets(this, listOf(pointer)) { targets
      ->
      val isCurrentResult = isVisible && generation == currentGeneration
      if (disposed || !isCurrentResult) return@resolveTargets
      val target = targets.singleOrNull() as? Navigatable ?: return@resolveTargets
      if (target.canNavigate()) target.navigate(requestFocus)
    }
  }

  /** Old async rows cannot start a new navigation request during result replacement. */
  private fun selectedNavigationNode(): MetroTreeNode? {
    if (disposed || !isVisible) return null
    val node = tree.selectionPath?.let(::nodeAt) ?: return null
    return node.takeIf { structure.contains(it) }
  }

  override fun dispose() {
    disposed = true
    generation++
    structure.clear()
    clearDiagnosticSelection()
  }
}

/** Builds result rows from one immutable run snapshot without querying the browser's index. */
internal class MetroValidationResultTreeStructure(private val project: Project) :
  AbstractTreeStructure() {
  private val root = MetroTreeNode.Root()
  @Volatile private var resultsByPath: Map<GraphPath, CachedValidation>? = null

  /** Publishes one snapshot for subsequent background children requests. */
  fun showResults(results: List<KaGraphValidationResult>) {
    resultsByPath = results.associate { it.context.path to CachedValidation(it, stale = false) }
  }

  /**
   * A replaced or evicted result remains historical; unrelated retained runs never enter this view.
   */
  fun refreshStaleness(retainedResults: List<CachedValidation>): Boolean {
    val displayed = resultsByPath ?: return false
    val retainedByPath = retainedResults.associateBy { it.result.context.path }
    var changed = false
    val updated = displayed.mapValues { (path, cached) ->
      val retained = retainedByPath[path]
      val isSameResult = retained?.result === cached.result
      val stale = !isSameResult || retained?.stale == true
      if (stale == cached.stale) {
        cached
      } else {
        changed = true
        CachedValidation(cached.result, stale)
      }
    }
    if (changed) resultsByPath = updated
    return changed
  }

  /** Drops result ownership when the view closes or its owner is disposed. */
  fun clear() {
    resultsByPath = null
  }

  /** Rejects rows retained by the async tree while a replacement run is loading. */
  fun contains(node: MetroTreeNode): Boolean {
    var current: MetroTreeNode? = node
    while (current != null) {
      when (current) {
        is MetroTreeNode.Validation -> {
          val result = current.result
          return resultsByPath?.get(result.context.path)?.result === result
        }
        is MetroTreeNode.Graph ->
          return resultsByPath?.get(current.context.path)?.result?.context === current.context
        else -> current = current.parent
      }
    }
    return false
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
              .map { cached ->
                ProgressManager.checkCanceled()
                val result = cached.result
                val summary = validationSummary(result) + if (cached.stale) " · stale" else ""
                MetroTreeNode.Graph(
                  root,
                  result.context,
                  result.context.presentableName(includeFile = true),
                  summary,
                )
              }
              .sortedWith(compareBy({ it.context.path.segments.size }, { it.text }))
          }
        }
        is MetroTreeNode.Graph -> {
          val cached = results[element.context.path] ?: return emptyArray()
          listOf(MetroTreeNode.Validation(element, cached.result, stale = cached.stale))
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
