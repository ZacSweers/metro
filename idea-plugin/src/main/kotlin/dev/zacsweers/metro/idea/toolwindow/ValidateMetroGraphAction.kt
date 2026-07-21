// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.toolwindow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.util.parentOfType
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.idea.MetroIdeProjectService
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
    val ktClass = graphClassAt(e) ?: return
    val classId = ktClass.getClassId() ?: return
    openAndValidate(project, classId, ktClass.containingFile?.virtualFile)
  }

  /** The graph class at the caret, detected by annotation short names without resolution. */
  private fun graphClassAt(e: AnActionEvent): KtClassOrObject? {
    val project = e.project ?: return null
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
    val file = e.getData(CommonDataKeys.PSI_FILE) ?: return null
    val element = file.findElementAt(editor.caretModel.offset) ?: return null
    val ktClass = element.parentOfType<KtClassOrObject>(withSelf = true) ?: return null

    val options = project.service<MetroIdeProjectService>().state(ktClass).options
    if (!options.enabled) return null
    val graphShortNames =
      (options.dependencyGraphAnnotations + options.graphExtensionAnnotations).mapToSet {
        it.shortClassName.asString()
      }
    val isGraph = ktClass.annotationEntries.any { it.shortName?.asString() in graphShortNames }
    return ktClass.takeIf { isGraph }
  }

  companion object {
    const val TOOL_WINDOW_ID = "Metro"

    /** Activates the Metro tool window, selects [classId]'s graph, and validates it. */
    fun openAndValidate(project: Project, classId: ClassId, file: VirtualFile?) {
      val toolWindow =
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
      toolWindow.activate {
        val panel =
          toolWindow.contentManager.contents.firstOrNull()?.component as? MetroToolWindowPanel
        panel?.selectAndValidate(classId, file)
      }
    }
  }
}
