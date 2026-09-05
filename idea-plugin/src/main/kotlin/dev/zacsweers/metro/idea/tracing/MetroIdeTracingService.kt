// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import dev.zacsweers.metro.idea.MetroSettings
import dev.zacsweers.metro.idea.index.MetroResolutionService
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly

/** Project-local capture controls. Recording never changes graph-refresh or validation policy. */
@Service(Service.Level.PROJECT)
internal class MetroIdeTracingService(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  private var recorder =
    IdeTraceRecorder(
      scope,
      { failure ->
        createIdeTraceOutput(
          PathManager.getLogDir(),
          failure,
          includeThreadActivity = MetroSettings.getInstance(project).state.includeThreadActivity,
        )
      },
      ::captureFinished,
    )

  @Volatile
  var isRefreshCapture: Boolean = false
    private set

  val state
    get() = recorder.state

  fun startCapture() {
    if (project.isDisposed || !MetroSettings.getInstance(project).state.enableDebuggingOptions)
      return
    if (state.value != IdeTraceState.IDLE) return
    isRefreshCapture = false
    recorder.start()
  }

  /** Captures one accepted refresh through index publication, including any admitted work. */
  fun refreshWithTracing() {
    if (project.isDisposed || !MetroSettings.getInstance(project).state.enableDebuggingOptions)
      return
    val resolution = project.service<MetroResolutionService>()
    if (resolution.isExplicitGraphRefreshPending || state.value != IdeTraceState.IDLE) return
    isRefreshCapture = true
    recorder.startRequest {
      recorder.traceSuspend("refresh") { trace ->
        val request =
          withContext(Dispatchers.EDT) {
            if (project.isDisposed) null else resolution.refreshGraphData()
          }
        if (request == null) {
          trace?.outcome("not_started")
          recorder.stop(IdeTraceStopReason.NOT_STARTED)
          return@traceSuspend
        }
        trace?.attribute("manualRequest", request.id)
        trace?.attribute("scope", "index_publication_and_admitted_work")
        val outcome = request.completion.await()
        trace?.outcome(outcome.name.lowercase())
      }
    }
  }

  fun stopCapture() = recorder.stop()

  fun settingsChanged() {
    if (!MetroSettings.getInstance(project).state.enableDebuggingOptions) {
      recorder.stop(IdeTraceStopReason.DEBUGGING_DISABLED)
    }
  }

  fun addStateListener(parentDisposable: Disposable, listener: (IdeTraceState) -> Unit) {
    val job = scope.launch(Dispatchers.EDT) { state.collectLatest { listener(it) } }
    Disposer.register(parentDisposable, Disposable { job.cancel() })
  }

  fun <T> trace(
    name: String,
    metadata: IdeTraceOperation.() -> Unit = {},
    block: (IdeTraceOperation?) -> T,
  ): T = recorder.trace(name, metadata, block)

  suspend fun <T> traceSuspend(
    name: String,
    metadata: IdeTraceOperation.() -> Unit = {},
    block: suspend (IdeTraceOperation?) -> T,
  ): T = recorder.traceSuspend(name, metadata, block)

  fun event(name: String, metadata: IdeTraceOperation.() -> Unit = {}) =
    recorder.event(name, metadata)

  /** Installs an isolated recorder before a fixture begins, preserving real service entrypoints. */
  @TestOnly
  internal fun setRecorderForTest(value: IdeTraceRecorder): IdeTraceRecorder {
    check(state.value == IdeTraceState.IDLE)
    val previous = recorder
    recorder = value
    return previous
  }

  private suspend fun captureFinished(
    path: Path?,
    failure: Throwable?,
    reason: IdeTraceStopReason,
    requestCapture: Boolean,
  ) {
    val partial = requestCapture && reason != IdeTraceStopReason.COMPLETED
    withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext
      if (failure != null) {
        StatusBar.Info.set(
          "Metro performance trace failed (${failure.javaClass.simpleName})",
          project,
        )
      } else if (path != null) {
        try {
          RevealFileAction.openFile(path.toFile())
          val message =
            if (partial && reason == IdeTraceStopReason.DEADLINE)
              "Partial Metro performance trace saved: the 10-minute recording limit was reached"
            else if (reason == IdeTraceStopReason.NOT_STARTED)
              "Metro performance trace saved; refresh was not started"
            else if (partial) "Partial Metro performance trace saved"
            else "Metro performance trace saved"
          StatusBar.Info.set(message, project)
        } catch (failure: Exception) {
          rethrowTraceControlFlow(failure)
          val description =
            if (partial) "Partial Metro performance trace" else "Metro performance trace"
          StatusBar.Info.set("$description saved to $path", project)
        }
      }
    }
  }
}
