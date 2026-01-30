// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.tracing

import androidx.tracing.TraceDriver
import androidx.tracing.Tracer
import androidx.tracing.wire.TraceDriver
import androidx.tracing.wire.TraceSink
import java.nio.file.Path
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import okio.blackholeSink
import okio.buffer

@IgnorableReturnValue
context(scope: TraceScope)
internal inline fun <T> trace(
  name: String,
  category: String = scope.tracer.category,
  crossinline block: TraceScope.() -> T,
): T {
  return scope.tracer.trace(category = category, name = name) { scope.block() }
}

@JvmInline
internal value class TracingSession(private val traceDriver: TraceDriver) : AutoCloseable {
  val tracer: Tracer
    get() = traceDriver.tracer

  override fun close() {
    traceDriver.close()
  }

  companion object {
    fun create(tracePath: Path?): TracingSession {
      val sink =
        if (tracePath == null) {
          TraceSink(sequenceId = 1, blackholeSink().buffer(), EmptyCoroutineContext)
        } else {
          tracePath.deleteIfExists()
          tracePath.createDirectories()
          TraceSink(sequenceId = 1, directory = tracePath.toFile())
        }
      val driver = TraceDriver(sink = sink, isEnabled = tracePath != null)
      return TracingSession(driver)
    }
  }
}

internal interface TraceScope {
  val tracer: MetroTracer

  val diagnosticTag: String
    get() = tracer.category.replace('.', '_')

  companion object {
    operator fun invoke(tracer: Tracer, category: String): TraceScope =
      TraceScopeImpl(MetroTracer(tracer, category))
  }
}

@JvmInline internal value class TraceScopeImpl(override val tracer: MetroTracer) : TraceScope
