// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.currentThreadCoroutineScope
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.parentOfType
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.index.snapshot.annotationShortNamesIncludingAliases
import dev.zacsweers.metro.idea.model.GraphPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject

/**
 * Editor action on a `@DependencyGraph`/`@GraphExtension` declaration. Opens the Metro tool window,
 * selects the graph, and validates it.
 */
internal class ValidateMetroGraphAction : AnAction(), DumbAware {

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = graphClassAt(e) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return
    val offset = editor.caretModel.offset
    val pinnedPath = project.service<GraphContextPinService>().pinnedPath
    val requestToken = project.service<MetroValidationRequestService>().beginRequest()
    currentThreadCoroutineScope().launch {
      val target =
        readAction {
          val ktClass = graphClassAt(project, file, offset) ?: return@readAction null
          val classId = ktClass.getClassId() ?: return@readAction null
          val graphFile = ktClass.containingFile.virtualFile
          GraphValidationTarget(
            classId,
            graphFile,
            graphValidationPath(classId, graphFile, pinnedPath),
          )
        } ?: return@launch
      withContext(Dispatchers.EDT) {
        openAndValidate(project, target.classId, target.file, requestToken, target.path)
      }
    }
  }

  /** The graph class at the caret, detected by annotation short names without resolution. */
  private fun graphClassAt(e: AnActionEvent): KtClassOrObject? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    return graphClassAt(project, file, editor.caretModel.offset)
  }

  private fun graphClassAt(project: Project, file: PsiFile, offset: Int): KtClassOrObject? {
    if (!file.isValid) return null
    val element = file.findElementAt(offset) ?: return null
    val ktClass = element.parentOfType<KtClassOrObject>(withSelf = true) ?: return null

    val state = project.service<MetroIdeProjectService>().state(ktClass)
    if (!state.isEnabled) return null
    val options = state.options
    val graphShortNames =
      ktClass.containingKtFile.annotationShortNamesIncludingAliases(
        options.dependencyGraphAnnotations + options.graphExtensionAnnotations
      )
    val isGraph = ktClass.annotationEntries.any { it.shortName?.asString() in graphShortNames }
    return ktClass.takeIf { isGraph }
  }

  companion object {
    const val TOOL_WINDOW_ID = "Metro"

    /** Activates the Metro tool window, selects [classId]'s graph, and validates it. */
    fun openAndValidate(
      project: Project,
      classId: ClassId,
      file: VirtualFile?,
      requestToken: Long = project.service<MetroValidationRequestService>().beginRequest(),
      path: GraphPath? =
        graphValidationPath(classId, file, project.service<GraphContextPinService>().pinnedPath),
    ) {
      val toolWindow =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
      toolWindow.activate {
        val panel =
          toolWindow.contentManager.contents.firstOrNull()?.component as? MetroToolWindowPanel
        panel?.selectAndValidate(classId, file, requestToken, path)
      }
    }
  }
}
