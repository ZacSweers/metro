// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.EventMetadata
import com.intellij.openapi.progress.ProcessCanceledException
import dev.zacsweers.metro.compiler.tracing.TraceScope
import kotlinx.coroutines.CancellationException

/** One finite operation. Children use its capture until the enclosing operation returns. */
internal class IdeTraceOperation
internal constructor(
  private val capture: IdeTraceCapture,
  val name: String,
  private val parentId: Long? = null,
) : TraceScope by capture.traceScope {
  private val id = capture.nextOperationId.incrementAndGet()
  private val attributes = linkedMapOf<String, String>()
  private var result: String? = null
  private var readAttempts = 0
  private var canceledReadAttempts = 0
  private var readNanos = 0L

  fun attribute(name: String, value: String) {
    synchronized(attributes) { attributes[name] = value }
  }

  fun attribute(name: String, value: Long) = attribute(name, value.toString())

  fun attribute(name: String, value: Int) = attribute(name, value.toString())

  fun attribute(name: String, value: Boolean) = attribute(name, value.toString())

  /** An explicit outcome survives cancellation handling in the surrounding trace wrapper. */
  fun outcome(value: String) {
    synchronized(attributes) { result = value }
  }

  internal fun child(name: String) = IdeTraceOperation(capture, name, id)

  internal fun <T> read(block: () -> T): T {
    val started = capture.nanoTime()
    var canceled = false
    try {
      return block()
    } catch (failure: Throwable) {
      canceled = failure is CancellationException || failure is ProcessCanceledException
      throw failure
    } finally {
      synchronized(attributes) {
        readAttempts++
        if (canceled) canceledReadAttempts++
        readNanos += capture.nanoTime() - started
      }
    }
  }

  /** Emits the outcome after work, when cache counts and publication decisions are known. */
  internal fun finish(failure: Throwable?, started: Long) {
    synchronized(attributes) {
      if (result == null) {
        result =
          when (failure) {
            null -> "completed"
            is CancellationException,
            is ProcessCanceledException -> "canceled"
            else -> "failed"
          }
      }
      attributes["outcome"] = checkNotNull(result)
      attributes["elapsed_ns"] = (capture.nanoTime() - started).toString()
      if (readAttempts > 0) {
        attributes["read_attempts"] = readAttempts.toString()
        attributes["canceled_read_attempts"] = canceledReadAttempts.toString()
        attributes["read_elapsed_ns"] = readNanos.toString()
      }
    }
    instant("$name.result")
  }

  internal fun instant(eventName: String = name) {
    capture.record {
      tracer.instant(category = category, name = eventName) { writeMetadata(this) }
    }
  }

  private fun writeMetadata(metadata: EventMetadata) {
    metadata.addMetadataEntry("operation_id", id.toString())
    parentId?.let { metadata.addMetadataEntry("parent_operation_id", it.toString()) }
    synchronized(attributes) {
      for ((key, value) in attributes) metadata.addMetadataEntry(key, value)
    }
  }

  /** A writer failure must never execute user work twice or replace its exception. */
  internal fun <T> run(block: (IdeTraceOperation?) -> T): T {
    val started = capture.nanoTime()
    var entered = false
    var completed = false
    var value: Any? = null
    var workFailure: Throwable? = null
    var traceFailure: Throwable? = null
    try {
      return tracer.trace(category, name, metadataBlock = { writeMetadata(this) }) {
        entered = true
        try {
          block(this@IdeTraceOperation).also {
            value = it
            completed = true
          }
        } catch (failure: Throwable) {
          workFailure = failure
          throw failure
        }
      }
    } catch (failure: Throwable) {
      workFailure?.let { throw it }
      traceFailure = failure
      rethrowTraceControlFlow(failure)
      capture.failed(failure)
      if (completed) {
        @Suppress("UNCHECKED_CAST")
        return value as T
      }
      check(!entered)
      try {
        return block(null)
      } catch (failure: Throwable) {
        workFailure = failure
        throw failure
      }
    } finally {
      finish(workFailure ?: traceFailure, started)
    }
  }

  internal suspend fun <T> runSuspend(block: suspend (IdeTraceOperation?) -> T): T {
    val started = capture.nanoTime()
    var entered = false
    var completed = false
    var value: Any? = null
    var workFailure: Throwable? = null
    var traceFailure: Throwable? = null
    try {
      return tracer.traceCoroutine(category, name, metadataBlock = { writeMetadata(this) }) {
        entered = true
        try {
          block(this@IdeTraceOperation).also {
            value = it
            completed = true
          }
        } catch (failure: Throwable) {
          workFailure = failure
          throw failure
        }
      }
    } catch (failure: Throwable) {
      workFailure?.let { throw it }
      traceFailure = failure
      rethrowTraceControlFlow(failure)
      capture.failed(failure)
      if (completed) {
        @Suppress("UNCHECKED_CAST")
        return value as T
      }
      check(!entered)
      try {
        return block(null)
      } catch (failure: Throwable) {
        workFailure = failure
        throw failure
      }
    } finally {
      finish(workFailure ?: traceFailure, started)
    }
  }
}

/** Nullable contexts keep disabled instrumentation free of clocks and metadata construction. */
internal fun <T> IdeTraceOperation?.phase(
  name: String,
  block: (IdeTraceOperation?) -> T,
): T = if (this == null) block(null) else child(name).run(block)

internal suspend fun <T> IdeTraceOperation?.phaseSuspend(
  name: String,
  block: suspend (IdeTraceOperation?) -> T,
): T = if (this == null) block(null) else child(name).runSuspend(block)

/** Accumulates active read attempts on the enclosing phase without producing per-file events. */
internal fun <T> IdeTraceOperation?.readAttempt(block: () -> T): T =
  if (this == null) block() else read(block)

/** Platform cancellation and fatal VM errors retain their normal control-flow semantics. */
internal fun rethrowTraceControlFlow(failure: Throwable) {
  when (failure) {
    is CancellationException,
    is ProcessCanceledException,
    is VirtualMachineError,
    is ThreadDeath -> throw failure
  }
}
