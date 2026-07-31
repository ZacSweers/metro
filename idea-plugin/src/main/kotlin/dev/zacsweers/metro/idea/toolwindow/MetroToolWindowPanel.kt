// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
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
import dev.zacsweers.metro.idea.MetroIcons
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.KaGraphNode
import java.awt.BorderLayout
import java.awt.event.MouseEvent
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
  private val treeStructure = MetroTreeStructure(project) { searchField.text }
  private val treeModel = StructureTreeModel(treeStructure, this)
  private val tree =
    Tree(AsyncTreeModel(treeModel, this)).apply {
      isRootVisible = false
      showsRootHandles = true
    }

  init {
    TreeSpeedSearch.installOn(tree)

    object : DoubleClickListener() {
        override fun onDoubleClick(event: MouseEvent): Boolean = navigateSelected()
      }
      .installOn(tree)

    searchField.addDocumentListener(
      object : DocumentAdapter() {
        override fun textChanged(e: DocumentEvent) {
          treeModel.invalidateAsync()
        }
      }
    )

    val actionGroup =
      DefaultActionGroup(
        // Not DumbAware: the refreshed tree needs stub indexes, so wait for smart mode
        object : AnAction("Refresh", "Reload graphs and bindings", AllIcons.Actions.Refresh) {
          override fun actionPerformed(e: AnActionEvent) {
            treeModel.invalidateAsync()
          }
        },
        object :
          AnAction("Validate", "Validate the selected graph", MetroIcons.GRAPH_VALIDATED),
          DumbAware {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

          override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = selectedGraph() != null
          }

          override fun actionPerformed(e: AnActionEvent) {
            selectedGraph()?.let(::validateGraph)
          }
        },
      )
    val toolbar =
      ActionManager.getInstance().createActionToolbar("MetroToolWindow", actionGroup, true)
    toolbar.targetComponent = tree

    val header = JPanel(BorderLayout())
    header.add(toolbar.component, BorderLayout.WEST)
    header.add(searchField, BorderLayout.CENTER)
    setToolbar(header)
    setContent(JBScrollPane(tree))
  }

  /** Expands to [classId]'s graph node, selects it, and runs validation. */
  fun selectAndValidate(classId: ClassId, file: VirtualFile?) {
    TreeUtil.promiseSelect(tree, graphVisitor(classId, file)).onProcessed {
      // Validate even when the tree has no matching node yet (still loading, or the graph's
      // module isn't the one the tree rendered from)
      val graph = selectedGraph() ?: findGraph(classId, file)
      graph?.let(::validateGraph)
    }
  }

  /** Resolves [classId]'s graph straight from its file's index, bypassing the tree. */
  private fun findGraph(classId: ClassId, file: VirtualFile?): KaGraphNode? {
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

  private fun selectedGraph(): KaGraphNode? {
    var node = selectedNode()
    while (node != null) {
      if (node is MetroTreeNode.Graph) return node.graph
      node = node.parent
    }
    return null
  }

  private fun validateGraph(graph: KaGraphNode) {
    val element = graph.pointer.element ?: return
    project.service<MetroGraphValidationService>().validateWithExtensionsAsync(element, graph) {
      // Rerun highlighting so the gutter's validation badge picks up the new result
      DaemonCodeAnalyzer.getInstance(project).restart()
      // Select the validation node once the refreshed children load, so the outcome is visible
      // even when the run produced no problems.
      treeModel.invalidateAsync().thenRun {
        SwingUtilities.invokeLater {
          TreeUtil.promiseSelect(tree, validationVisitor(graph))
        }
      }
    }
  }

  private fun validationVisitor(graph: KaGraphNode): TreeVisitor {
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

  private fun navigateSelected(): Boolean {
    val target = selectedNode()?.pointer?.element as? Navigatable ?: return false
    if (!target.canNavigate()) return false
    target.navigate(true)
    return true
  }

  override fun dispose() {}
}
