// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.navigation

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroNavigationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.BindingIndex
import kotlinx.coroutines.Job
import org.jetbrains.kotlin.psi.KtFile

/** Captures the editor state that owns a cold lookup and its later chooser callbacks. */
internal class MetroEditorRequest(private val editor: Editor) {
  private val project = checkNotNull(editor.project)
  private val pinService = project.service<GraphContextPinService>()
  val offset = editor.caretModel.offset
  val pinnedPath = pinService.pinnedPath
  private val stamp = editor.document.modificationStamp

  /** A chooser can stay open while its editor changes or closes. */
  fun isCurrent(): Boolean {
    if (project.isDisposed || editor.isDisposed) return false
    val sourceIsCurrent =
      editor.document.modificationStamp == stamp && editor.caretModel.offset == offset
    return sourceIsCurrent && pinService.pinnedPath == pinnedPath
  }

  /** Rechecks ownership at item selection, after a popup may have remained open for some time. */
  fun <T> guard(onChosen: (T) -> Unit): (T) -> Unit = { value ->
    if (isCurrent()) onChosen(value)
  }
}

/** Cold lookup waits outside read access, then retains its editor snapshot through UI selection. */
internal fun requestMetroEditorTargets(
  editor: Editor,
  file: KtFile,
  onResolved: (MetroEditorTargets, MetroEditorRequest) -> Unit,
): Job? {
  return requestMetroEditorData(
    editor,
    file,
    query = { index, request ->
      metroEditorTargets(index, file, request.offset, request.pinnedPath)
    },
    onResolved = onResolved,
  )
}

/** Shares cold-index retry and stale-editor guards across explicit editor queries. */
internal fun <T : Any> requestMetroEditorData(
  editor: Editor,
  file: KtFile,
  query: (BindingIndex, MetroEditorRequest) -> T,
  onResolved: (T, MetroEditorRequest) -> Unit,
): Job? {
  val project = editor.project ?: return null
  val request = MetroEditorRequest(editor)
  return project
    .service<MetroNavigationService>()
    .runEditorRequest(
      editor,
      resolve = {
        retryCancelledIndexBuild {
          smartReadAction(project) {
            if (!file.isValid) return@smartReadAction null
            val index = project.service<MetroResolutionService>().currentIndex(file)
            query(index, request)
          }
        }
      },
      onResolved = { result ->
        if (result != null && request.isCurrent()) onResolved(result, request)
      },
    )
}
