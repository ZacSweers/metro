// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.AbstractTraceDriver
import dev.zacsweers.metro.compiler.tracing.TraceScope
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class IdeTraceState {
  IDLE,
  STARTING,
  RECORDING,
  STOPPING,
}

/** The output is assigned on IO before returning across a cancellable dispatcher boundary. */
internal class IdeTraceOutput(
  val driver: AbstractTraceDriver,
  val path: Path? = null,
  val timeline: IdeTraceTimeline? = null,
) {
  fun close() {
    driver.close()
    timeline?.writeTo(checkNotNull(path))
  }
}

/** Owns admission and leases. Published IDE data must never retain this capture. */
internal class IdeTraceCapture(
  val traceScope: TraceScope,
  val nanoTime: () -> Long,
  private val onFailure: (Throwable) -> Unit,
  val timeline: IdeTraceTimeline? = null,
) {
  val nextOperationId = AtomicLong()
  private val lock = Any()
  private var accepting = true
  private var users = 0
  val drained = CompletableDeferred<Unit>()

  fun acquire(): Boolean =
    synchronized(lock) {
      if (!accepting) return false
      users++
      true
    }

  fun release() {
    synchronized(lock) {
      check(users > 0)
      users--
      if (!accepting && users == 0) drained.complete(Unit)
    }
  }

  fun stop() {
    synchronized(lock) {
      accepting = false
      if (users == 0) drained.complete(Unit)
    }
  }

  fun failed(failure: Throwable) = onFailure(failure)

  fun record(block: () -> Unit) {
    try {
      block()
    } catch (failure: Throwable) {
      rethrowTraceControlFlow(failure)
      failed(failure)
    }
  }
}

/**
 * A capture owner survives cancellation long enough to drain admitted operations and close IO. The
 * deadline bounds admission; existing operations finish with their original capture.
 */
internal class IdeTraceRecorder(
  private val scope: CoroutineScope,
  private val createOutput: ((Throwable) -> Unit) -> IdeTraceOutput,
  private val onFinished: suspend (Path?, Throwable?) -> Unit = { _, _ -> },
  private val durationMillis: Long = 60_000,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private class Request {
    val stop = CompletableDeferred<Unit>()
    val failure = AtomicReference<Throwable?>()
    var output: IdeTraceOutput? = null
    var capture: IdeTraceCapture? = null
  }

  private val lock = Any()
  private val active = AtomicReference<IdeTraceCapture?>()
  private val mutableState = MutableStateFlow(IdeTraceState.IDLE)
  val state = mutableState.asStateFlow()
  private var current: Request? = null

  fun start() {
    synchronized(lock) {
      if (current != null) return
      val request = Request()
      current = request
      mutableState.value = IdeTraceState.STARTING
      // Install finally even if the owning service has already been canceled.
      scope.launch(start = CoroutineStart.UNDISPATCHED) { own(request) }
    }
  }

  fun stop() {
    synchronized(lock) {
      val request = current ?: return
      detach(request)
      request.stop.complete(Unit)
    }
  }

  private fun detach(request: Request) {
    request.capture?.stop()
    active.set(null)
    mutableState.value = IdeTraceState.STOPPING
  }

  private fun fail(request: Request, failure: Throwable) {
    request.failure.compareAndSet(null, failure)
    synchronized(lock) {
      if (current !== request) return
      detach(request)
      request.stop.complete(Unit)
    }
  }

  private suspend fun own(request: Request) {
    try {
      currentCoroutineContext().ensureActive()
      withContext(Dispatchers.IO) {
        request.output = createOutput { fail(request, it) }
      }
      val output = checkNotNull(request.output)
      val capture =
        IdeTraceCapture(
          TraceScope(output.driver.tracer, "metro.ide"),
          nanoTime,
          onFailure = { fail(request, it) },
          timeline = output.timeline,
        )
      synchronized(lock) {
        request.capture = capture
        if (request.stop.isCompleted) {
          capture.stop()
        } else {
          active.set(capture)
          mutableState.value = IdeTraceState.RECORDING
        }
      }
      withTimeoutOrNull(durationMillis) { request.stop.await() }
    } catch (failure: Throwable) {
      rethrowTraceControlFlow(failure)
      fail(request, failure)
    } finally {
      synchronized(lock) { detach(request) }
      withContext(NonCancellable + Dispatchers.IO) {
        try {
          request.capture?.drained?.await()
          request.output?.close()
        } catch (failure: Throwable) {
          rethrowTraceControlFlow(failure)
          request.failure.compareAndSet(null, failure)
        } finally {
          synchronized(lock) {
            current = null
            mutableState.value = IdeTraceState.IDLE
          }
        }
      }
    }
    onFinished(request.output?.path, request.failure.get())
  }

  fun <T> trace(
    name: String,
    metadata: IdeTraceOperation.() -> Unit = {},
    block: (IdeTraceOperation?) -> T,
  ): T {
    val capture = active.get()
    if (capture == null || !capture.acquire()) return block(null)
    try {
      val operation = IdeTraceOperation(capture, name)
      capture.record { operation.metadata() }
      return operation.run(block)
    } finally {
      capture.release()
    }
  }

  suspend fun <T> traceSuspend(
    name: String,
    metadata: IdeTraceOperation.() -> Unit = {},
    block: suspend (IdeTraceOperation?) -> T,
  ): T {
    val capture = active.get()
    if (capture == null || !capture.acquire()) return block(null)
    try {
      val operation = IdeTraceOperation(capture, name)
      capture.record { operation.metadata() }
      return operation.runSuspend(block)
    } finally {
      capture.release()
    }
  }

  /** Coordinator decisions can follow worker completion without retaining a worker's capture. */
  fun event(name: String, metadata: IdeTraceOperation.() -> Unit = {}) {
    val capture = active.get()
    if (capture == null || !capture.acquire()) return
    try {
      val operation = IdeTraceOperation(capture, name)
      capture.record { operation.metadata() }
      operation.instant()
    } finally {
      capture.release()
    }
  }
}
