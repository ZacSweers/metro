// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.navigation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import dev.zacsweers.metro.idea.MetroIdeProjectService
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.index.showMetroNavigationTargets
import dev.zacsweers.metro.idea.toolwindow.MetroToolWindowPanel
import dev.zacsweers.metro.idea.toolwindow.ValidateMetroGraphAction
import org.jetbrains.kotlin.psi.KtFile

/** Explicit binding navigation at the caret, available through Find Action and the editor menu. */
internal class GoToMetroBindingAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val enabled = metroEditorActionAvailable(e)
    val showNavigation =
      e.project?.let { MetroSettings.getInstance(it).state.enableBindingResolution }
    e.presentation.isEnabledAndVisible = enabled && showNavigation == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val file = e.getData(CommonDataKeys.PSI_FILE) as? KtFile ?: return
    requestMetroEditorTargets(editor, file) { targets, request ->
      chooseTarget(
        editor,
        request,
        "Choose Metro graph context",
        "No Metro binding at the caret",
        targets.navigation,
      ) { choice ->
        chooseTarget(
          editor,
          request,
          "Metro bindings in ${choice.text}",
          "No Metro binding found in ${choice.text}",
          choice.bindings,
        ) { binding ->
          showMetroNavigationTargets(
            editor,
            "Metro binding",
            "The Metro binding is no longer available",
            listOf(binding.pointer),
            request::isCurrent,
          )
        }
      }
    }
  }
}

/** Reveals a graph or binding using its concrete graph path without changing the editor pin. */
internal class SelectInMetroAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = metroEditorActionAvailable(e)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val file = e.getData(CommonDataKeys.PSI_FILE) as? KtFile ?: return
    requestMetroEditorTargets(editor, file) { targets, request ->
      chooseTarget(
        editor,
        request,
        "Select in Metro",
        "No applicable Metro graph at the caret",
        targets.reveal,
      ) { target ->
        val project = editor.project ?: return@chooseTarget
        val toolWindow =
          ToolWindowManager.getInstance(project)
            .getToolWindow(ValidateMetroGraphAction.TOOL_WINDOW_ID) ?: return@chooseTarget
        toolWindow.activate {
          if (!request.isCurrent()) return@activate
          val panel =
            toolWindow.contentManager.contents.firstOrNull()?.component as? MetroToolWindowPanel
          panel?.reveal(target) { revealed ->
            if (!revealed) {
              JBPopupFactory.getInstance()
                .createMessage(
                  "The requested row is unavailable. Refresh Metro graph data and try again."
                )
                .showInFocusCenter()
            }
          }
        }
      }
    }
  }
}

/** Action updates only check project configuration and the presence of a Kotlin editor. */
internal fun metroEditorActionAvailable(e: AnActionEvent): Boolean {
  val project = e.project ?: return false
  val file = e.getData(CommonDataKeys.PSI_FILE) as? KtFile ?: return false
  if (e.getData(CommonDataKeys.EDITOR) == null) return false
  return project.service<MetroIdeProjectService>().state(file).isEnabled
}

/**
 * The chooser renders captured strings and opens one concrete context before binding navigation.
 */
internal fun <T> chooseTarget(
  editor: Editor,
  request: MetroEditorRequest,
  title: String,
  emptyText: String,
  choices: List<T>,
  onChosen: (T) -> Unit,
) {
  val chooseCurrent = request.guard(onChosen)
  when (choices.size) {
    0 -> JBPopupFactory.getInstance().createMessage(emptyText).showInBestPositionFor(editor)
    1 -> chooseCurrent(choices.single())
    else ->
      JBPopupFactory.getInstance()
        .createPopupChooserBuilder(choices)
        .setTitle(title)
        .setItemChosenCallback(chooseCurrent)
        .createPopup()
        .showInBestPositionFor(editor)
  }
}
