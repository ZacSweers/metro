// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.explanation

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import dev.zacsweers.metro.idea.navigation.chooseTarget
import dev.zacsweers.metro.idea.navigation.metroEditorActionAvailable
import dev.zacsweers.metro.idea.navigation.requestMetroEditorData
import org.jetbrains.kotlin.psi.KtFile

/** An explicit selection explanation, computed only when the user requests it. */
internal class ExplainMetroBindingAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = metroEditorActionAvailable(e)
  }

  override fun actionPerformed(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR) ?: return
    val file = e.getData(CommonDataKeys.PSI_FILE) as? KtFile ?: return
    requestMetroEditorData(
      editor,
      file,
      query = { index, request ->
        metroBindingExplanations(index, file, request.offset, request.pinnedPath)
      },
    ) { explanations, request ->
      chooseTarget(
        editor,
        request,
        "Choose Metro dependency and graph context",
        "Place the caret on a Metro dependency used by a graph",
        explanations,
      ) { explanation ->
        MetroBindingExplanationDialog(file.project, explanation).show()
      }
    }
  }
}
