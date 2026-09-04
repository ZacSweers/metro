// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import dev.zacsweers.metro.idea.MetroSettings
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
      { failure -> createIdeTraceOutput(PathManager.getLogDir(), failure) },
      ::captureFinished,
    )

  val state
    get() = recorder.state

  fun startCapture() {
    if (project.isDisposed || !MetroSettings.getInstance(project).state.enableDebuggingOptions)
      return
    recorder.start()
  }

  fun stopCapture() = recorder.stop()

  fun settingsChanged() {
    if (!MetroSettings.getInstance(project).state.enableDebuggingOptions) stopCapture()
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

  private suspend fun captureFinished(path: Path?, failure: Throwable?) {
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
          StatusBar.Info.set("Metro performance trace saved", project)
        } catch (failure: Exception) {
          rethrowTraceControlFlow(failure)
          StatusBar.Info.set("Metro performance trace saved to $path", project)
        }
      }
    }
  }
}
