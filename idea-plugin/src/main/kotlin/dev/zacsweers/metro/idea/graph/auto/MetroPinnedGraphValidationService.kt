// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph.auto

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import dev.zacsweers.metro.idea.GraphContextPinService
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.psi.KtFile

/** Owns one debounced validation of the pinned graph and its extension children. */
@Service(Service.Level.PROJECT)
internal class MetroPinnedGraphValidationService(
  private val project: Project,
  private val scope: CoroutineScope,
) : Disposable {
  private val disposed = AtomicBoolean()
  @Volatile private var pending: Job? = null
  @Volatile private var running: Job? = null

  init {
    project.service<MetroResolutionService>().addIndexListener(this) { requestValidation() }
    project.messageBus
      .connect(this)
      .subscribe(
        DumbService.DUMB_MODE,
        object : DumbService.DumbModeListener {
          override fun enteredDumbMode() {
            requestValidation()
          }

          override fun exitDumbMode() {
            requestValidation()
          }
        },
      )
  }

  /** Cancels superseded work when graph data, the pin, or the user's settings change. */
  fun requestValidation(): Job? {
    ApplicationManager.getApplication().assertIsDispatchThread()
    pending?.cancel()
    pending = null
    running?.cancel()
    running = null
    if (!isEnabled()) return null
    val path = project.service<GraphContextPinService>().pinnedPath ?: return null
    val request =
      scope.launch(Dispatchers.EDT) {
        delay(DEBOUNCE_MILLIS)
        if (!isCurrent(path)) return@launch
        val validation = project.service<MetroGraphValidationService>()
        val alreadyCurrent =
          validation.retainedResults().any {
            !it.stale && it.result.context.path == path
          }
        if (alreadyCurrent || validation.isValidationRunning(path)) return@launch
        val context = cachedContext(path) ?: return@launch
        if (!isCurrent(path) || validation.isValidationRunning(path)) return@launch
        // Explicit validation can replace this request immediately through the same service.
        val validationJob =
          validation.validateWithExtensionsAsync(
            context,
            showProgress = false,
            allowIndexBuild = false,
          ) {}
        running = validationJob
        try {
          validationJob.join()
        } finally {
          if (!isActive) validationJob.cancel()
          if (running === validationJob) running = null
        }
      }
    pending = request
    return request
  }

  private fun isCurrent(path: GraphPath): Boolean {
    return isEnabled() && project.service<GraphContextPinService>().pinnedPath == path
  }

  /** Resolves the pin from published data without turning a passive request into an index build. */
  private suspend fun cachedContext(path: GraphPath): GraphContext? {
    val compilationFile =
      path.dynamicGraphId?.callerFile ?: path.segments.lastOrNull()?.file ?: return null
    return smartReadAction(project) {
      if (!isCurrent(path)) return@smartReadAction null
      val file =
        PsiManager.getInstance(project).findFile(compilationFile) as? KtFile
          ?: return@smartReadAction null
      val index = project.service<MetroResolutionService>().cachedIndex(file)
      if (index === BindingIndex.EMPTY) return@smartReadAction null
      index.withResolutionSession { it.findContext(path) }
    }
  }

  private fun isEnabled(): Boolean {
    if (disposed.get() || project.isDisposed || DumbService.isDumb(project)) return false
    val settings = MetroSettings.getInstance(project).state
    if (!settings.automaticallyValidatePinnedGraph || !settings.automaticallyRefreshGraphData)
      return false
    return !project.service<MetroResolutionService>().isGraphDataRefreshRequired
  }

  override fun dispose() {
    disposed.set(true)
    pending?.cancel()
    pending = null
    running?.cancel()
    running = null
  }

  private companion object {
    const val DEBOUNCE_MILLIS = 750L
  }
}
