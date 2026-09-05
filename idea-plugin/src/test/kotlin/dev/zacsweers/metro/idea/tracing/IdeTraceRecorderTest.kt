// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.wire.TraceDriver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Exercises ownership and emitted events without starting an IDE or touching the filesystem. */
class IdeTraceRecorderTest : TestCase() {
  fun testDisabledCaptureSkipsFactoryClockAndMetadata() = runBlocking {
    val job = SupervisorJob()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(job + Dispatchers.Default),
        createOutput = { error("Created disabled output") },
        nanoTime = { error("Read disabled clock") },
      )
    try {
      val result =
        recorder.trace("disabled", { error("Built disabled metadata") }) { operation ->
          assertNull(operation)
          operation.phase("phase") { phase -> phase.readAttempt { 42 } }
        }
      assertEquals(42, result)
      assertEquals(43, recorder.traceSuspend("disabled") { it.phaseSuspend("phase") { 43 } })
      recorder.event("disabled") { error("Built disabled event") }
      recorder.stop()
      assertEquals(IdeTraceState.IDLE, recorder.state.value)
    } finally {
      job.cancelAndJoin()
    }
  }

  fun testStopDrainsConcurrentOperationsAndRejectsNewOnes() = withRecorder { recorder, sink ->
    val release = CompletableDeferred<Unit>()
    val entered = List(2) { CompletableDeferred<Unit>() }
    val operations = entered.mapIndexed { index, ready ->
      async(Dispatchers.Default) {
        recorder.traceSuspend("operation.$index") { operation ->
          assertNotNull(operation)
          ready.complete(Unit)
          release.await()
          operation.phase("child.$index") { 42 }
        }
      }
    }
    try {
      entered.forEach { it.await() }
      recorder.stop()
      recorder.stop()
      recorder.start()
      assertEquals(IdeTraceState.DRAINING, recorder.state.value)
      assertEquals(0, sink.closeCount)
      recorder.trace("late") { assertNull(it) }
    } finally {
      release.complete(Unit)
    }
    operations.forEach { assertEquals(42, it.await()) }
    recorder.state.first { it == IdeTraceState.IDLE }
    assertEquals(1, sink.closeCount)
    assertEquals(1, sink.results("operation.0").size)
    assertEquals(1, sink.results("operation.1").size)
    assertTrue(sink.events.none { it.name == "late" })
    for (index in 0..1) {
      val parent = sink.results("operation.$index").single()
      val child = sink.results("child.$index").single()
      assertEquals(parent.metadata["operation_id"], child.metadata["parent_operation_id"])
    }
  }

  fun testReadAttemptsAreAggregatedAndExplicitOutcomeSurvivesCancellation() =
    withRecorder { recorder, sink ->
      val failure = CancellationException("Superseded")
      try {
        recorder.trace("scan") { operation ->
          operation.readAttempt { Unit }
          operation?.outcome("superseded")
          operation.readAttempt { throw failure }
        }
        fail("Expected cancellation")
      } catch (actual: CancellationException) {
        assertSame(failure, actual)
      }
      recorder.stop()
      recorder.state.first { it == IdeTraceState.IDLE }
      val result = sink.results("scan").single().metadata
      assertEquals("superseded", result["outcome"])
      assertEquals("2", result["read_attempts"])
      assertEquals("1", result["canceled_read_attempts"])
      assertTrue(checkNotNull(result["read_elapsed_ns"]).toLong() >= 0)
    }

  fun testWorkExceptionAndNullReturnArePreserved() = withRecorder { recorder, sink ->
    val failure = IllegalArgumentException("Work failed")
    var calls = 0
    try {
      recorder.trace("failure") {
        calls++
        throw failure
      }
      fail("Expected work exception")
    } catch (actual: IllegalArgumentException) {
      assertSame(failure, actual)
    }
    assertEquals(1, calls)
    assertNull(recorder.trace("nullable") { null })
    recorder.stop()
    recorder.state.first { it == IdeTraceState.IDLE }
    assertEquals("failed", sink.results("failure").single().metadata["outcome"])
  }

  fun testCoroutinePhaseClosesAfterSynchronousChildAndThreadSwitch() =
    withRecorder { recorder, sink ->
      var calls = 0
      val result =
        async(Dispatchers.Unconfined) {
            recorder.traceSuspend("moving") { operation ->
              calls++
              val initialThread = Thread.currentThread().threadId()
              operation.phase("moving.child") { 42 }
              // Unconfined resumes on the worker so the parent closes on a different thread.
              withContext(Dispatchers.Default) {
                assertTrue(initialThread != Thread.currentThread().threadId())
              }
              43
            }
          }
          .await()
      assertEquals(43, result)
      assertEquals(1, calls)
      assertEquals(IdeTraceState.RECORDING, recorder.state.value)
      recorder.stop()
      recorder.state.first { it == IdeTraceState.IDLE }
      assertEquals("completed", sink.results("moving").single().metadata["outcome"])
      assertEquals("completed", sink.results("moving.child").single().metadata["outcome"])
    }

  fun testRequestCaptureWaitsForOutputAndUsesItsOwnDeadline() = runBlocking {
    val owner = SupervisorJob()
    val creating = CompletableDeferred<Unit>()
    val outputReady = CountDownLatch(1)
    val entered = CompletableDeferred<Unit>()
    val finishRefresh = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<IdeTraceStopReason>()
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        {
          creating.complete(Unit)
          check(outputReady.await(10, TimeUnit.SECONDS))
          IdeTraceOutput(TraceDriver(sink))
        },
        onFinished = { _, failure, reason, _ ->
          assertNull(failure)
          finished.complete(reason)
        },
        durationMillis = 25,
        requestDurationMillis = 10_000,
      )
    sink.onClose = { assertEquals(IdeTraceState.SAVING, recorder.state.value) }
    try {
      withTimeout(10_000) {
        assertTrue(
          recorder.startRequest {
            recorder.traceSuspend("refresh") {
              entered.complete(Unit)
              finishRefresh.await()
            }
          }
        )
        creating.await()
        assertEquals(IdeTraceState.STARTING, recorder.state.value)
        assertFalse(entered.isCompleted)
        assertFalse(recorder.startRequest { error("Duplicate request") })
        outputReady.countDown()
        entered.await()
        delay(75)
        assertEquals(IdeTraceState.RECORDING, recorder.state.value)
        finishRefresh.complete(Unit)
        assertEquals(IdeTraceStopReason.COMPLETED, finished.await())
        assertEquals(1, sink.closeCount)
      }
    } finally {
      outputReady.countDown()
      finishRefresh.complete(Unit)
      owner.cancelAndJoin()
    }
  }

  fun testStoppingRequestCaptureLeavesRefreshAliveAndDrainsAdmittedWork() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val refresh = CompletableDeferred<Int>()
    val observing = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<IdeTraceStopReason>()
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink)) },
        onFinished = { _, failure, reason, _ ->
          assertNull(failure)
          finished.complete(reason)
        },
      )
    try {
      withTimeout(10_000) {
        recorder.startRequest {
          recorder.traceSuspend("refresh") {
            observing.complete(Unit)
            refresh.await()
          }
        }
        observing.await()
        val work =
          async(Dispatchers.Default) {
            recorder.traceSuspend("candidate") {
              entered.complete(Unit)
              release.await()
            }
          }
        entered.await()
        recorder.stop()
        assertEquals(IdeTraceState.DRAINING, recorder.state.value)
        assertFalse(refresh.isCancelled)
        assertFalse(refresh.isCompleted)
        assertEquals(0, sink.closeCount)
        release.complete(Unit)
        work.await()
        assertEquals(IdeTraceStopReason.USER, finished.await())
        refresh.complete(42)
        assertEquals(42, refresh.await())
        val stop = sink.events.single { it.name == "capture.finish" }.metadata
        assertEquals("user", stop["stop_reason"])
        assertEquals("true", stop["partial"])
      }
    } finally {
      release.complete(Unit)
      refresh.complete(42)
      owner.cancelAndJoin()
    }
  }

  fun testRequestSafetyDeadlineSavesAPartialCaptureWithoutCancelingRefresh() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val refresh = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<IdeTraceStopReason>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink)) },
        onFinished = { _, failure, reason, _ ->
          assertNull(failure)
          finished.complete(reason)
        },
        requestDurationMillis = 25,
      )
    try {
      withTimeout(10_000) {
        recorder.startRequest { refresh.await() }
        assertEquals(IdeTraceStopReason.DEADLINE, finished.await())
        assertFalse(refresh.isCancelled)
        assertFalse(refresh.isCompleted)
        val stop = sink.events.single { it.name == "capture.finish" }.metadata
        assertEquals("deadline", stop["stop_reason"])
        assertEquals("true", stop["partial"])
      }
    } finally {
      refresh.complete(Unit)
      owner.cancelAndJoin()
    }
  }

  fun testAutomaticStopAndRestartUseSeparateOutputs() = runBlocking {
    val job = SupervisorJob()
    val finished = kotlinx.coroutines.channels.Channel<Unit>(2)
    val sinks = java.util.concurrent.CopyOnWriteArrayList<RecordingIdeTraceSink>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(job + Dispatchers.Default),
        createOutput = {
          val sink = RecordingIdeTraceSink()
          sinks += sink
          IdeTraceOutput(TraceDriver(sink))
        },
        onFinished = { _, failure, _, _ ->
          assertNull(failure)
          finished.send(Unit)
        },
        durationMillis = 25,
      )
    try {
      withTimeout(10_000) {
        repeat(2) {
          recorder.start()
          finished.receive()
          assertEquals(IdeTraceState.IDLE, recorder.state.value)
        }
      }
      assertEquals(2, sinks.size)
      assertTrue(sinks.all { it.closeCount == 1 })
    } finally {
      job.cancelAndJoin()
      finished.close()
    }
  }

  fun testOwnerCancellationWaitsForOperationsFromAnotherScope() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink)) },
      )
    recorder.start()
    val release = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    val operation =
      launch(Dispatchers.Default) {
        recorder.state.first { it == IdeTraceState.RECORDING }
        recorder.traceSuspend("external") {
          entered.complete(Unit)
          release.await()
        }
      }
    try {
      withTimeout(10_000) {
        entered.await()
        owner.cancel()
        recorder.state.first { it == IdeTraceState.DRAINING }
        assertEquals(0, sink.closeCount)
        operation.cancelAndJoin()
        owner.join()
      }
      assertEquals(1, sink.closeCount)
      assertEquals("canceled", sink.results("external").single().metadata["outcome"])
    } finally {
      release.complete(Unit)
      operation.cancelAndJoin()
      owner.cancelAndJoin()
    }
  }

  fun testCancellationDuringCreationClosesUnpublishedOutput() = runBlocking {
    val owner = SupervisorJob()
    val creating = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        {
          creating.complete(Unit)
          check(release.await(10, TimeUnit.SECONDS))
          IdeTraceOutput(TraceDriver(sink))
        },
      )
    try {
      recorder.start()
      withTimeout(10_000) { creating.await() }
      owner.cancel()
    } finally {
      release.countDown()
      withTimeout(10_000) { owner.cancelAndJoin() }
    }
    assertEquals(IdeTraceState.IDLE, recorder.state.value)
    assertEquals(1, sink.closeCount)
  }

  fun testSetupAndCloseFailuresReturnToIdle() = runBlocking {
    val owner = SupervisorJob()
    val failure = IOException("Cannot create output")
    val failed = CompletableDeferred<Throwable?>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        createOutput = { throw failure },
        onFinished = { _, error, _, _ -> failed.complete(error) },
      )
    try {
      recorder.start()
      val reported = withTimeout(10_000) { failed.await() }
      assertEquals(IOException::class.java, reported?.javaClass)
      assertEquals(failure.message, reported?.message)
      assertEquals(IdeTraceState.IDLE, recorder.state.value)
    } finally {
      owner.cancelAndJoin()
    }
    withRecorder { recording, sink ->
      sink.closeFailure = failure
      recording.stop()
      recording.state.first { it == IdeTraceState.IDLE }
      assertEquals(1, sink.closeCount)
    }
  }

  fun testWriterFailureStopsAdmissionAndPreservesRunningWork() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val reportFailure = CompletableDeferred<(Throwable) -> Unit>()
    val finished = CompletableDeferred<Throwable?>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        createOutput = { onFailure ->
          reportFailure.complete(onFailure)
          IdeTraceOutput(TraceDriver(sink))
        },
        onFinished = { _, failure, _, _ -> finished.complete(failure) },
      )
    val release = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    var calls = 0
    try {
      withTimeout(10_000) {
        recorder.start()
        recorder.state.first { it == IdeTraceState.RECORDING }
        val work =
          async(Dispatchers.Default) {
            recorder.traceSuspend("work") {
              calls++
              entered.complete(Unit)
              release.await()
              42
            }
          }
        entered.await()
        val failure = IOException("Writer failed")
        reportFailure.await()(failure)
        assertEquals(IdeTraceState.DRAINING, recorder.state.value)
        assertEquals(0, sink.closeCount)
        recorder.trace("late") { assertNull(it) }
        release.complete(Unit)
        assertEquals(42, work.await())
        assertSame(failure, finished.await())
        assertEquals(1, calls)
        assertEquals(1, sink.closeCount)
      }
    } finally {
      release.complete(Unit)
      owner.cancelAndJoin()
    }
  }

  fun testWritesAFileWithSynchronousAndCoroutinePhases() = runBlocking {
    val owner = SupervisorJob()
    val saved = CompletableDeferred<Path>()
    val directory = Path.of("build", "trace-smoke")
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { failure -> createIdeTraceOutput(directory, failure) },
        onFinished = { path, failure, _, _ ->
          if (failure != null) saved.completeExceptionally(failure)
          else saved.complete(checkNotNull(path))
        },
      )
    try {
      withTimeout(10_000) {
        recorder.start()
        recorder.state.first { it == IdeTraceState.RECORDING }
        recorder.traceSuspend("refresh", { attribute("manualRequest", 1) }) { operation ->
          operation.phase("source.scan") { scan ->
            // Match a large refresh so the exported trace exercises thousands of item intervals.
            scan?.attribute("files.total", 2296)
            val work = IdeTraceWorkSummary(checkNotNull(scan), "source.file")
            repeat(2296) { index ->
              work.measure { item ->
                item?.file = "src/File$index.kt"
                item?.module = "app"
                item.measureRead {
                  item.stage("source.file.annotationLookup") { 42 }
                  item.stage("source.file.cacheLookup") {
                    item.stage("source.file.declarationExtraction") { 43 }
                  }
                }
              }
            }
            work.report()
          }
          operation.phase("source.resolveClassRequests") { resolution ->
            val work = IdeTraceWorkSummary(checkNotNull(resolution), "source.class")
            repeat(4218) { index ->
              work.measure { item ->
                item?.className = "example.Class$index"
                item?.module = "app"
                item.measureRead {
                  item.stage("source.class.findClass") { 42 }
                  item.stage("source.class.cacheCheck") { 43 }
                }
              }
            }
            work.report()
          }
          withContext(Dispatchers.Default) {
            operation.phase("smoke.seal") { Unit }
          }
          operation?.outcome("published")
        }
        recorder.stop()
        val path = saved.await()
        assertTrue(Files.size(path) > 0)
        assertTrue(path.fileName.toString().endsWith(".perfetto-trace"))
      }
    } finally {
      owner.cancelAndJoin()
    }
  }

  private fun withRecorder(
    block: suspend CoroutineScope.(IdeTraceRecorder, RecordingIdeTraceSink) -> Unit
  ) = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val clock = AtomicLong()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink)) },
        nanoTime = { clock.incrementAndGet() },
      )
    try {
      withTimeout(10_000) {
        recorder.start()
        recorder.state.first { it == IdeTraceState.RECORDING }
        block(recorder, sink)
      }
    } finally {
      recorder.stop()
      withTimeout(10_000) { owner.cancelAndJoin() }
    }
  }
}
