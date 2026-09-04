// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.DelicateTracingApi
import androidx.tracing.EventMetadata
import androidx.tracing.recordExceptionAndThrow
import com.intellij.openapi.progress.ProcessCanceledException
import dev.zacsweers.metro.compiler.tracing.TraceScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** One finite operation. Children use its capture until the enclosing operation returns. */
internal class IdeTraceOperation
internal constructor(
  private val capture: IdeTraceCapture,
  val name: String,
  private val parentId: Long? = null,
  private val rootId: Long? = null,
  private val context: Map<String, String> = emptyMap(),
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

  internal fun child(name: String): IdeTraceOperation {
    val inherited =
      synchronized(attributes) { context + attributes.filterKeys { it in CONTEXT_KEYS } }
    return IdeTraceOperation(capture, name, id, rootId ?: id, inherited)
  }

  internal fun nowNanos(): Long = capture.nanoTime()

  /** Summaries retain primitive metadata and stay with their enclosing operation's capture. */
  fun event(name: String, metadata: IdeTraceOperation.() -> Unit = {}) {
    capture.record {
      child(name).apply(metadata).instant()
    }
  }

  /**
   * Returns whether detail was recorded. Reserves before metadata emits children, and skips
   * metadata when full. Priority preserves space for selected slow work after coarse admission
   * ends.
   */
  fun completedPhase(
    name: String,
    started: Long,
    finished: Long,
    priority: Boolean = false,
    metadata: IdeTraceOperation.() -> Unit = {},
  ): Boolean {
    var recorded = false
    capture.record {
      val timeline = capture.timeline
      if (timeline == null) {
        child(name).apply(metadata).finish(null, started, finished)
        recorded = true
        return@record
      }
      val reservation = timeline.reserveDetail(priority) ?: return@record
      try {
        val phase = child(name)
        var failure: Throwable? = null
        try {
          phase.metadata()
        } catch (caught: Throwable) {
          failure = caught
          throw caught
        } finally {
          // Metadata may already have emitted children. Preserve their admitted parent on failure.
          recorded = phase.finish(failure, started, finished, reservation)
        }
      } finally {
        timeline.releaseDetail(reservation)
      }
    }
    return recorded
  }

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
  internal fun finish(
    failure: Throwable?,
    started: Long,
    finished: Long = capture.nanoTime(),
    reservation: IdeTraceTimeline.DetailReservation? = null,
  ): Boolean {
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
      attributes["elapsed_ns"] = (finished - started).toString()
      attributes["started_ns"] = started.toString()
      if (readAttempts > 0) {
        attributes["read_attempts"] = readAttempts.toString()
        attributes["canceled_read_attempts"] = canceledReadAttempts.toString()
        attributes["read_elapsed_ns"] = readNanos.toString()
      }
    }
    val timeline = capture.timeline
    if (timeline == null) {
      instant("$name.result")
      return true
    }
    var recorded = false
    capture.record {
      val interval =
        IdeTraceInterval(id, parentId, rootId ?: id, name, started, finished, metadataSnapshot())
      if (reservation == null) timeline.record(interval)
      else timeline.recordReservedDetail(reservation, interval)
      recorded = true
    }
    return recorded
  }

  internal fun instant(eventName: String = name) {
    capture.record {
      val timeline = capture.timeline
      if (timeline == null) {
        tracer.instant(category = category, name = eventName) { writeMetadata(this) }
      } else {
        val timestamp = capture.nanoTime()
        timeline.record(
          IdeTraceInterval(
            id,
            parentId,
            rootId ?: id,
            eventName,
            timestamp,
            null,
            metadataSnapshot(),
          )
        )
      }
    }
  }

  private fun metadataSnapshot(): Map<String, String> =
    synchronized(attributes) {
      buildMap {
        putAll(context)
        putAll(attributes)
        put("operation", name)
        put("operation_id", id.toString())
        parentId?.let { put("parent_operation_id", it.toString()) }
      }
    }

  private fun writeMetadata(metadata: EventMetadata) {
    for ((key, value) in metadataSnapshot()) metadata.addMetadataEntry(key, value)
  }

  /** A writer failure must never execute user work twice or replace its exception. */
  internal fun <T> run(block: (IdeTraceOperation?) -> T): T {
    val started = capture.nanoTime()
    // Optional live slices share operation IDs with the completed logical intervals.
    if (capture.timeline != null && !capture.includeThreadActivity) {
      var failure: Throwable? = null
      try {
        return block(this)
      } catch (caught: Throwable) {
        failure = caught
        throw caught
      } finally {
        finish(failure, started)
      }
    }
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
    // AndroidX records coroutine resumptions and their flows while finish retains the wall span.
    if (capture.timeline != null && !capture.includeThreadActivity) {
      var failure: Throwable? = null
      try {
        return block(this)
      } catch (caught: Throwable) {
        failure = caught
        throw caught
      } finally {
        finish(failure, started)
      }
    }
    var entered = false
    var completed = false
    var value: Any? = null
    var workFailure: Throwable? = null
    var traceFailure: Throwable? = null
    try {
      return traceCoroutineWithOwnedHandle {
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

  /**
   * Captures the coroutine handle before same-thread child events reuse AndroidX's mutable holder.
   */
  @OptIn(DelicateTracingApi::class)
  private suspend fun <T> traceCoroutineWithOwnedHandle(block: suspend () -> T): T {
    if (!tracer.isCategoryEnabled(category)) return block()
    val section = tracer.beginCoroutineSection(category, name, token = null) { writeMetadata(this) }
    val closeable = section.closeable
    try {
      val contextElement = section.propagationToken.contextElementOrNull()
      if (contextElement == null) return block()
      return withContext(contextElement) { block() }
    } catch (failure: Throwable) {
      tracer.recordExceptionAndThrow(category, "$name.exception", failure)
    } finally {
      closeable.close()
    }
  }

  private companion object {
    val CONTEXT_KEYS =
      setOf("manualRequest", "intent", "generation", "request", "file", "class", "graph", "module")
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
    is VirtualMachineError -> throw failure
  }
}
