// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.wire.TraceDriver
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Verifies logical wall-time intervals, late attribution, and concurrent lane placement. */
class IdeTraceTimelineTest : TestCase() {
  fun testSuspensionKeepsOneIntervalAndFinalMetadata() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val timeline = IdeTraceTimeline()
    val clock = AtomicLong(100)
    val output = Files.createTempFile("metro-logical-timeline-", ".perfetto-trace")
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink), output, timeline) },
        nanoTime = clock::get,
      )
    try {
      recorder.start()
      withTimeout(10_000) { recorder.state.first { it == IdeTraceState.RECORDING } }
      recorder.traceSuspend("index.candidate") { operation ->
        operation?.attribute("manualRequest", 12)
        clock.set(110)
        operation.phaseSuspend("source.scan") { scan ->
          withContext(Dispatchers.Default) { clock.set(200) }
          scan?.attribute("files.total", 2297)
          scan?.attribute("files_rebuilt", 2296)
        }
        clock.set(210)
        operation?.outcome("published")
      }
      val lane = timeline.lanes().single()
      val spans = lane.intervals.filter { it.finished != null }
      assertEquals(2, spans.size)
      val scan = spans.single { it.name == "source.scan" }
      assertEquals(110L, scan.started)
      assertEquals(200L, scan.finished)
      assertEquals("12", scan.attributes["manualRequest"])
      assertEquals("2296", scan.attributes["files_rebuilt"])
      assertEquals(
        "Analyze source declarations: 2297 files",
        ideTraceDisplayName(scan.name, scan.attributes),
      )
      assertEquals("published", spans.single { it.name == "index.candidate" }.attributes["outcome"])
      assertTrue(sink.events.none { it.name == "source.scan" })
    } finally {
      recorder.stop()
      owner.cancelAndJoin()
      Files.deleteIfExists(output)
    }
  }

  fun testCrossingSiblingsUseSeparateLanesAndRetainNestedChildren() {
    val timeline = IdeTraceTimeline()
    timeline.record(span(1, null, 0, 100))
    timeline.record(span(2, 1, 10, 60))
    timeline.record(span(3, 1, 20, 80))
    timeline.record(span(4, 3, 30, 50))
    timeline.record(span(5, 1, 80, 90))
    val lanes = timeline.lanes()
    assertEquals(2, lanes.size)
    assertEquals(listOf(1L, 2L, 5L), lanes[0].intervals.map { it.id })
    assertEquals(listOf(3L, 4L), lanes[1].intervals.map { it.id })
  }

  fun testCompletedIntervalsAreBounded() {
    val timeline = IdeTraceTimeline(capacity = 2)
    repeat(10) { index -> timeline.record(span(index + 1L, null, index * 10L, index * 10L + 1)) }
    assertEquals(2, timeline.lanes().sumOf { it.intervals.size })
  }

  fun testOverviewCoversConcurrentWorkAndRetainsPartialCaptureReason() {
    val timeline = IdeTraceTimeline()
    assertNull(timeline.overview())
    timeline.record(IdeTraceInterval(1, null, 1, "refresh", 20, 80, mapOf("manualRequest" to "12")))
    timeline.record(IdeTraceInterval(2, null, 2, "index.candidate", 30, 100, emptyMap()))
    timeline.record(IdeTraceInterval(3, null, 3, "index.classifyPsi", 10, 15, emptyMap()))
    timeline.record(
      IdeTraceInterval(
        4,
        null,
        4,
        "capture.finish",
        110,
        null,
        mapOf("partial" to "true", "stop_reason" to "user"),
      )
    )
    val overview = checkNotNull(timeline.overview())
    assertEquals(10L, overview.started)
    assertEquals(100L, overview.finished)
    assertEquals("90", overview.attributes["elapsed_ns"])
    assertEquals("12", overview.attributes["manualRequest"])
    assertEquals("true", overview.attributes["partial"])
    assertEquals("user", overview.attributes["stop_reason"])
  }

  private fun span(id: Long, parent: Long?, start: Long, end: Long) =
    IdeTraceInterval(id, parent, 1, "operation.$id", start, end, emptyMap())
}
