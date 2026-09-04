// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import dev.zacsweers.metro.idea.GIT_SHA
import dev.zacsweers.metro.idea.VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import okio.buffer
import okio.sink

/** Called on IO. The serializer remains alive until the capture owner drains and closes it. */
internal fun createIdeTraceOutput(
  directory: Path,
  onFailure: (Throwable) -> Unit,
  includeThreadActivity: Boolean = false,
): IdeTraceOutput {
  Files.createDirectories(directory)
  val path = Files.createTempFile(directory, "metro-ide-", ".perfetto-trace")
  val bufferedSink = path.toFile().sink().buffer()
  var sink: TraceSink? = null
  try {
    val handler = CoroutineExceptionHandler { _, failure -> onFailure(failure) }
    sink = TraceSink(1, bufferedSink, NonCancellable + Dispatchers.IO + handler)
    val driver = TraceDriver(sink)
    driver.tracer.instant(category = "metro.ide", name = "capture") {
      addMetadataEntry("plugin_version", VERSION)
      addMetadataEntry("plugin_git_sha", GIT_SHA)
      addMetadataEntry("include_thread_activity", includeThreadActivity.toString())
    }
    return IdeTraceOutput(driver, path, IdeTraceTimeline(), includeThreadActivity)
  } catch (failure: Throwable) {
    try {
      if (sink == null) bufferedSink.close() else sink.close()
    } catch (closeFailure: Throwable) {
      failure.addSuppressed(closeFailure)
    }
    throw failure
  }
}
