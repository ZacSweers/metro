// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.wire.TraceDriver
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/** Checks optional live thread scopes alongside the same logical operation intervals. */
class IdeTraceThreadActivityTest : TestCase() {
  fun testBothModesKeepLogicalIntervalsAndOnlyHybridEmitsLiveScopes() = runBlocking {
    worker("metro-trace-first").use { first ->
      worker("metro-trace-second").use { second ->
        for (enabled in listOf(false, true)) {
          val calls = mutableListOf<String>()
          val recorded =
            recordInMemory(enabled) { recorder ->
              val value = withContext(first) { exercisePhases(recorder, second, calls) }
              assertEquals(42, value)
            }
          assertEquals(listOf("sync", "suspend"), calls)
          val durations = recorded.intervals.filter { it.finished != null }
          val phaseNames =
            setOf(
              "index.candidate",
              "index.captureInputs",
              "source.scan",
              "source.resolveClassRequests",
            )
          assertEquals(phaseNames + "source.file.item", durations.map { it.name }.toSet())
          assertEquals(5, durations.size)
          assertTrue(recorded.intervals.none { it.name.endsWith(".result") })
          assertTrue(recorded.events.none { it.name == "source.file.item" })
          assertTrue(recorded.events.none { it.name?.endsWith(".result") == true })
          val scan = durations.single { it.name == "source.scan" }
          assertEquals("1", scan.attributes["files.total"])
          assertEquals("12", scan.attributes["manualRequest"])
          if (enabled) {
            for (name in phaseNames) {
              val logical = durations.single { it.name == name }
              val starts = recorded.events.filter { it.type == 1 && it.name == name }
              assertTrue("Missing live scope for $name", starts.isNotEmpty())
              assertTrue(
                "Missing correlation for $name",
                starts.any { it.metadata["operation_id"] == logical.attributes["operation_id"] },
              )
            }
            assertBalancedScopes(recorded.events)
          } else {
            assertTrue(recorded.events.none { it.name in phaseNames })
          }
        }
      }
    }
  }

  fun testHybridCancellationClosesNestedScopesAndExecutesWorkOnce() = runBlocking {
    worker("metro-trace-cancel-first").use { first ->
      worker("metro-trace-cancel-second").use { second ->
        var calls = 0
        val cancellation = CancellationException("Refresh superseded")
        val recorded =
          recordInMemory(includeThreadActivity = true) { recorder ->
            withContext(first) {
              try {
                recorder.traceSuspend("index.candidate") { root ->
                  root.phaseSuspend("source.scan") { scan ->
                    withContext(second) {
                      scan.phase("index.captureInputs") { calls++ }
                      yield()
                    }
                    throw cancellation
                  }
                }
                fail("Expected cancellation")
              } catch (actual: CancellationException) {
                assertEquals(cancellation.message, actual.message)
              }
            }
            assertEquals(IdeTraceState.RECORDING, recorder.state.value)
          }
        assertEquals(1, calls)
        val durations = recorded.intervals.filter { it.finished != null }
        assertEquals(3, durations.size)
        assertEquals(
          "canceled",
          durations.single { it.name == "index.candidate" }.attributes["outcome"],
        )
        assertEquals(
          "canceled",
          durations.single { it.name == "source.scan" }.attributes["outcome"],
        )
        assertEquals(
          "completed",
          durations.single { it.name == "index.captureInputs" }.attributes["outcome"],
        )
        assertBalancedScopes(recorded.events)
      }
    }
  }

  /** Keeps small real exports for Perfetto checks of thread tracks, flows, and logical bars. */
  fun testExportsBothModesWithThreadHandoffs() = runBlocking {
    worker("metro-trace-smoke-first").use { first ->
      worker("metro-trace-smoke-second").use { second ->
        for (enabled in listOf(false, true)) {
          val directory =
            Path.of(
              "build",
              "trace-smoke",
              "thread-activity",
              if (enabled) "enabled" else "disabled",
            )
          val calls = mutableListOf<String>()
          val path =
            withRecorder(
              createOutput = { failure -> createIdeTraceOutput(directory, failure, enabled) }
            ) { recorder ->
              val value = withContext(first) { exercisePhases(recorder, second, calls) }
              assertEquals(42, value)
            }
          assertEquals(listOf("sync", "suspend"), calls)
          assertTrue(Files.size(path) > 0)
        }
      }
    }
  }

  /**
   * Fixed executor ownership guarantees a thread handoff independently of dispatcher scheduling.
   */
  private fun worker(name: String) =
    Executors.newSingleThreadExecutor { task -> Thread(task, name) }.asCoroutineDispatcher()

  private suspend fun exercisePhases(
    recorder: IdeTraceRecorder,
    second: CoroutineDispatcher,
    calls: MutableList<String>,
  ): Int =
    recorder.traceSuspend("index.candidate", { attribute("manualRequest", 12) }) { root ->
      val base =
        root.phase("index.captureInputs") {
          calls += "sync"
          40
        }
      root.phaseSuspend("source.scan") { scan ->
        val firstThread = Thread.currentThread().threadId()
        val started = System.nanoTime()
        val value =
          withContext(second) {
            assertTrue(firstThread != Thread.currentThread().threadId())
            scan.phaseSuspend("source.resolveClassRequests") {
              calls += "suspend"
              yield()
              2
            }
          }
        val finished = System.nanoTime()
        checkNotNull(scan).completedPhase("source.file.item", started, finished) {
          attribute("file", "src/SmokeGraph.kt")
        }
        scan.attribute("files.total", 1)
        base + value
      }
    }

  private data class RecordedTrace(
    val intervals: List<IdeTraceInterval>,
    val events: List<RecordedIdeTraceEvent>,
  )

  private suspend fun recordInMemory(
    includeThreadActivity: Boolean,
    block: suspend (IdeTraceRecorder) -> Unit,
  ): RecordedTrace {
    val sink = RecordingIdeTraceSink()
    val timeline = IdeTraceTimeline()
    val path = Files.createTempFile("metro-thread-activity-", ".perfetto-trace")
    try {
      withRecorder(
        createOutput = { IdeTraceOutput(TraceDriver(sink), path, timeline, includeThreadActivity) },
        block = block,
      )
      assertEquals(1, sink.closeCount)
      return RecordedTrace(timeline.lanes().flatMap { it.intervals }, sink.events)
    } finally {
      Files.deleteIfExists(path)
    }
  }

  /** Awaiting completion observes flushed thread events and the appended logical export. */
  private suspend fun withRecorder(
    createOutput: ((Throwable) -> Unit) -> IdeTraceOutput,
    block: suspend (IdeTraceRecorder) -> Unit,
  ): Path {
    val owner = SupervisorJob()
    val saved = CompletableDeferred<Path>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        createOutput,
        onFinished = { path, failure, _, _ ->
          if (failure != null) saved.completeExceptionally(failure)
          else saved.complete(checkNotNull(path))
        },
      )
    try {
      return withTimeout(10_000) {
        recorder.start()
        recorder.state.first { it == IdeTraceState.RECORDING }
        block(recorder)
        recorder.stop()
        saved.await()
      }
    } finally {
      recorder.stop()
      owner.cancelAndJoin()
    }
  }

  private fun assertBalancedScopes(events: List<RecordedIdeTraceEvent>) {
    val starts = events.count { it.type == 1 }
    assertTrue("Expected live thread scopes", starts > 0)
    assertEquals(starts, events.count { it.type == 2 })
  }
}
