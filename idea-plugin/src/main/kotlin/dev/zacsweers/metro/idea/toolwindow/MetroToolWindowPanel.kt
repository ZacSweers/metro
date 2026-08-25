// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SearchTextField
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.tree.TreeVisitor
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.graph.GraphValidationProgress
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.presentableName
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.tree.TreePath
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtFile

/** The Metro tool window: browse graphs and their bindings, and run on-demand validation. */
internal class MetroToolWindowPanel(private val project: Project) :
  SimpleToolWindowPanel(true, true), Disposable {

  // No history popup, so the search icon doesn't render a misleading dropdown arrow
  private val searchField = SearchTextField(false)

  // The tree structure computes children on a background invoker, so it reads a snapshot of the
  // search text instead of touching the Swing component off the EDT.
  @Volatile private var searchText: String = ""
  private val resolutionService = project.service<MetroResolutionService>()
  private val validationService = project.service<MetroGraphValidationService>()
  private val pinService = project.service<GraphContextPinService>()
  private val indexBuildStatus = IndexBuildStatusPanel()
  private val validationStatus = ValidationStatusPanel()
  private var indexBuildProgress: IndexBuildProgress? = null
  private var validationProgress: List<GraphValidationProgress> = emptyList()
  private lateinit var actionToolbar: ActionToolbar
  private val treeStructure =
    MetroTreeStructure(project, resolutionService::indexForToolWindow, pinService) { searchText }

  /** A validate request whose graph was not indexed yet, retried when a fresh index lands. */
  private var pendingValidation: Pair<ClassId, VirtualFile?>? = null
  @Volatile private var disposed: Boolean = false
  private val treeModel = StructureTreeModel(treeStructure, this)
  private val tree =
    Tree(AsyncTreeModel(treeModel, this)).apply {
      isRootVisible = false
      showsRootHandles = true
    }
  internal val loadOrRefreshAction =
    LoadOrRefreshGraphsAction(resolutionService) {
      updateIndexBuildStatus()
      treeModel.invalidateAsync()
    }
  internal val graphContextSelectorAction =
    GraphContextSelectorAction(pinService, treeStructure::availableContexts)
  internal val pinSelectedGraphAction =
    PinSelectedGraphAction(pinService) { selectedGraphNode()?.context }

  init {
    TreeSpeedSearch.installOn(tree)

    // An activated window waiting on IDE indexes must retry once smart mode returns.
    project.messageBus
      .connect(this)
      .subscribe(
        DumbService.DUMB_MODE,
        object : DumbService.DumbModeListener {
          override fun enteredDumbMode() {
            updateIndexBuildStatus()
          }

          override fun exitDumbMode() {
            updateIndexBuildStatus()
            treeModel.invalidateAsync()
          }
        },
      )
    resolutionService.addIndexListener(this) {
      updateIndexBuildStatus()
      treeModel.invalidateAsync()
      pendingValidation?.let { (classId, file) ->
        // Invalidation and other modules publish through this same listener. Keep the request
        // until its own graph is available rather than dropping it on an unrelated update.
        findGraph(classId, file)?.let { graph ->
          pendingValidation = null
          validateGraph(graph)
        }
      }
    }
    pinService.addListener(this) { treeModel.invalidateAsync() }
    resolutionService.addIndexBuildProgressListener(this) { progress ->
      indexBuildProgress = progress
      updateIndexBuildStatus()
    }
    validationService.addValidationProgressListener(this) { progress ->
      validationProgress = progress
      updateValidationStatus()
    }

    object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean = navigateSelected()
      }
      .installOn(tree)

    searchField.addDocumentListener(
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          searchText = searchField.text
          treeModel.invalidateAsync()
        }
      }
    )
    tree.addTreeSelectionListener { updateValidationStatus() }

    val overflowGroup =
      DefaultActionGroup("More", true).apply {
        templatePresentation.icon = AllIcons.Actions.More
        add(ExportGraphDebugInfoAction(project) { selectedGraphNode()?.context })
      }
    val actionGroup =
      DefaultActionGroup(
        // Not DumbAware: the refreshed tree needs stub indexes, so wait for smart mode
        loadOrRefreshAction,
        graphContextSelectorAction,
        pinSelectedGraphAction,
        ValidateSelectedGraphAction(
          selectedContext = { selectedGraphNode()?.context },
          isValidationRunning = { validationService.isValidationRunning(it.path) },
          validate = ::validateContext,
        ),
        overflowGroup,
      )
    actionToolbar =
      ActionManager.getInstance().createActionToolbar("MetroToolWindow", actionGroup, true)
    actionToolbar.targetComponent = tree

    val header = JPanel(BorderLayout())
    header.add(actionToolbar.component, BorderLayout.WEST)
    header.add(searchField, BorderLayout.CENTER)
    setToolbar(header)
    val content = JPanel(BorderLayout())
    val statusContainer =
      JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(indexBuildStatus)
        add(validationStatus)
      }
    content.add(statusContainer, BorderLayout.NORTH)
    content.add(JBScrollPane(tree), BorderLayout.CENTER)
    setContent(content)
  }

  private fun updateIndexBuildStatus() {
    if (disposed || project.isDisposed) return
    if (DumbService.isDumb(project)) {
      indexBuildStatus.showWaitingForIdeIndexing()
    } else {
      val progress = indexBuildProgress
      when {
        progress != null -> indexBuildStatus.show(progress)
        !resolutionService.isGraphBrowserActivated -> indexBuildStatus.showNotLoaded()
        else -> indexBuildStatus.clear()
      }
    }
  }

  private fun updateValidationStatus() {
    if (disposed || project.isDisposed) return
    val selectedPath = selectedGraphNode()?.context?.path
    val selectedProgress = selectedPath?.let { path ->
      validationProgress.firstOrNull { it.covers(path) }
    }
    val progress = selectedProgress ?: validationProgress.firstOrNull()
    if (progress == null) {
      validationStatus.clear()
    } else {
      validationStatus.show(progress)
    }
    if (::actionToolbar.isInitialized) {
      actionToolbar.updateActionsImmediately()
    }
  }

  /** Expands to [classId]'s graph node, selects it, and runs validation. */
  fun selectAndValidate(classId: ClassId, file: VirtualFile?) {
    if (disposed) return
    TreeUtil.promiseSelect(tree, graphVisitor(classId, file)).onProcessed {
      if (disposed) return@onProcessed
      // Validate even when the tree has no matching node yet (still loading, or the graph's
      // module isn't the one the tree rendered from)
      val selectedGraph = selectedGraphNode()?.takeIf { it.matches(classId, file) }?.graph
      val graph = selectedGraph ?: findGraph(classId, file)
      if (graph != null) {
        pendingValidation = null
        validateGraph(graph)
      } else {
        // A cold index returns nothing on the EDT and builds in the background. Retry when the
        // fresh index lands instead of dropping the action.
        pendingValidation = classId to file
      }
    }
  }

  /** Resolves [classId]'s graph straight from its file's index, bypassing the tree. */
  private fun findGraph(classId: ClassId, file: VirtualFile?): KaGraphDeclaration? {
    val psiFile =
      file?.let { PsiManager.getInstance(project).findFile(it) } as? KtFile ?: return null
    return project.service<MetroResolutionService>().index(psiFile).graphs.firstOrNull {
      it.classId == classId && it.pointer.virtualFile == file
    }
  }

  private fun MetroTreeNode.Graph.matches(classId: ClassId?, file: VirtualFile?): Boolean {
    return graph.classId == classId && (file == null || graph.pointer.virtualFile == file)
  }

  private fun graphVisitor(classId: ClassId, file: VirtualFile?): TreeVisitor {
    return TreeVisitor { path ->
      when (val node = nodeAt(path)) {
        is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Graph ->
          if (node.matches(classId, file)) {
            TreeVisitor.Action.INTERRUPT
          } else {
            TreeVisitor.Action.SKIP_CHILDREN
          }
        else -> TreeVisitor.Action.SKIP_CHILDREN
      }
    }
  }

  private fun nodeAt(path: TreePath): MetroTreeNode? {
    return TreeUtil.getLastUserObject(MetroTreeNode::class.java, path)
      ?: TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element as? MetroTreeNode
  }

  private fun selectedNode(): MetroTreeNode? = tree.selectionPath?.let(::nodeAt)

  private fun selectedGraphNode(): MetroTreeNode.Graph? {
    var node = selectedNode()
    while (node != null) {
      if (node is MetroTreeNode.Graph) return node
      node = node.parent
    }
    return null
  }

  private fun validateGraph(graph: KaGraphDeclaration) {
    val element = graph.pointer.element ?: return
    validationService.validateWithExtensionsAsync(element, graph) {
      validationFinished(validationVisitor(graph))
    }
  }

  private fun validateContext(context: GraphContext) {
    val element = context.contextPointer.element ?: return
    validationService.validateWithExtensionsAsync(element, context) {
      validationFinished(validationVisitor(context))
    }
  }

  private fun validationFinished(visitor: TreeVisitor) {
    if (disposed || project.isDisposed) return
    // Rerun highlighting so the gutter's validation badge picks up the new result
    DaemonCodeAnalyzer.getInstance(project).restart()
    // Select the validation node once the refreshed children load, so the outcome is visible even
    // when the run produced no problems.
    treeModel.invalidateAsync().thenRun {
      SwingUtilities.invokeLater {
        if (disposed || project.isDisposed) return@invokeLater
        TreeUtil.promiseSelect(tree, visitor)
      }
    }
  }

  private fun validationVisitor(graph: KaGraphDeclaration): TreeVisitor {
    val file = graph.pointer.virtualFile
    return TreeVisitor { path ->
      when (val node = nodeAt(path)) {
        is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Graph ->
          if (node.matches(graph.classId, file)) {
            TreeVisitor.Action.CONTINUE
          } else {
            TreeVisitor.Action.SKIP_CHILDREN
          }
        is MetroTreeNode.Validation -> TreeVisitor.Action.INTERRUPT
        else -> TreeVisitor.Action.SKIP_CHILDREN
      }
    }
  }

  private fun validationVisitor(context: GraphContext): TreeVisitor {
    return TreeVisitor { path ->
      when (val node = nodeAt(path)) {
        is MetroTreeNode.Root -> TreeVisitor.Action.CONTINUE
        is MetroTreeNode.Graph ->
          if (node.context.path == context.path) {
            TreeVisitor.Action.CONTINUE
          } else {
            TreeVisitor.Action.SKIP_CHILDREN
          }
        is MetroTreeNode.Validation -> TreeVisitor.Action.INTERRUPT
        else -> TreeVisitor.Action.SKIP_CHILDREN
      }
    }
  }

  private fun navigateSelected(): Boolean {
    val target = selectedNode()?.pointer?.element as? Navigatable ?: return false
    if (!target.canNavigate()) return false
    target.navigate(true)
    return true
  }

  override fun dispose() {
    disposed = true
    pendingValidation = null
    indexBuildStatus.clear()
    validationStatus.clear()
  }
}

internal class ValidateSelectedGraphAction(
  private val selectedContext: () -> GraphContext?,
  private val isValidationRunning: (GraphContext) -> Boolean,
  private val validate: (GraphContext) -> Unit,
) : AnAction("Validate", "Validate the selected graph", MetroIcons.GRAPH_VALIDATED), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val context = selectedContext()
    e.presentation.isEnabled = context != null && !isValidationRunning(context)
  }

  override fun actionPerformed(e: AnActionEvent) {
    selectedContext()?.takeUnless(isValidationRunning)?.let(validate)
  }
}

internal class GraphContextSelectorAction(
  private val pinService: GraphContextPinService,
  private val contextProvider: () -> List<GraphContext>,
) : ComboBoxAction(), DumbAware {

  init {
    templatePresentation.text = "All Graphs"
    templatePresentation.description = "Choose the graph context used for editor presentation"
    templatePresentation.icon = MetroIcons.GRAPH
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = pinService.pinnedPath?.presentableName() ?: "All Graphs"
  }

  public override fun createPopupActionGroup(
    button: JComponent,
    dataContext: DataContext,
  ): DefaultActionGroup {
    val contextOptions = runReadActionBlocking {
      contextProvider().map { context ->
        context.presentableName(includeFile = true) to context.path
      }
    }
    return DefaultActionGroup().apply {
      add(GraphContextOptionAction("All Graphs", null, pinService))
      addSeparator()
      for ((name, path) in contextOptions) {
        add(GraphContextOptionAction(name, path, pinService))
      }
    }
  }
}

private class GraphContextOptionAction(
  text: String,
  private val path: GraphPath?,
  private val pinService: GraphContextPinService,
) : ToggleAction(text), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun isSelected(e: AnActionEvent): Boolean = pinService.pinnedPath == path

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    if (!state) return
    if (path == null) pinService.clear() else pinService.pin(path)
  }
}

internal class PinSelectedGraphAction(
  private val pinService: GraphContextPinService,
  private val selectedContext: () -> GraphContext?,
) : ToggleAction("Pin", "Pin the selected graph context", AllIcons.General.Pin_tab), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    super.update(e)
    val context = selectedContext()
    e.presentation.isEnabled = context != null
    e.presentation.description =
      if (context?.path == pinService.pinnedPath) {
        "Show all graph contexts"
      } else {
        "Pin the selected graph context"
      }
  }

  override fun isSelected(e: AnActionEvent): Boolean {
    return selectedContext()?.path == pinService.pinnedPath
  }

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val context = selectedContext() ?: return
    if (state) pinService.pin(context.path) else pinService.clearIf(context.path)
  }
}

internal class LoadOrRefreshGraphsAction(
  private val resolutionService: MetroResolutionService,
  private val refresh: () -> Unit,
) : AnAction("Load", "Load graphs and bindings", AllIcons.Actions.Refresh) {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun update(e: AnActionEvent) {
    val isActivated = resolutionService.isGraphBrowserActivated
    e.presentation.text = if (isActivated) "Refresh" else "Load"
    e.presentation.description =
      if (isActivated) "Refresh graphs and bindings" else "Load graphs and bindings"
  }

  override fun actionPerformed(e: AnActionEvent) {
    resolutionService.activateGraphBrowser()
    refresh()
  }
}
