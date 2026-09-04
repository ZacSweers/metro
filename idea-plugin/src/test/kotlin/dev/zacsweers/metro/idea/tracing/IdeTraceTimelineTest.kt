// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import androidx.tracing.wire.TraceDriver
import dev.zacsweers.metro.compiler.tracing.TraceScope
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
      assertTrue(lane.intervals.none { it.name.endsWith(".result") })
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

  fun testDetailSaturationPreservesRankedChildrenAndLaterPhaseSummary() {
    val timeline = IdeTraceTimeline(capacity = 8, enclosingReserve = 2, priorityReserve = 2)
    var now = 0L
    val capture =
      IdeTraceCapture(TraceScope.noop(), { now }, { throw AssertionError(it) }, timeline)
    var rejectedMetadataCalls = 0
    IdeTraceOperation(capture, "source.scan").run { operation ->
      checkNotNull(operation)
      repeat(4) { index ->
        assertTrue(operation.completedPhase("source.file.item", index * 2L, index * 2L + 1))
      }
      assertFalse(
        operation.completedPhase("source.file.item", 10, 11) {
          rejectedMetadataCalls++
          completedPhase("unexpected.child", 10, 11)
        }
      )
      assertTrue(
        operation.completedPhase("source.file.item", 20, 80, priority = true) {
          attribute("rank", 1)
          assertTrue(completedPhase("source.file.annotationScan", 30, 40, priority = true))
          assertFalse(
            completedPhase("source.file.shardConstruction", 50, 60, priority = true) {
              rejectedMetadataCalls++
              completedPhase("unexpected.child", 50, 60, priority = true)
            }
          )
        }
      )
      now = 90
      operation.event("source.file.summary")
      now = 100
    }
    val intervals = timeline.lanes().flatMap { it.intervals }
    assertEquals(0, rejectedMetadataCalls)
    assertEquals(8, intervals.size)
    assertEquals(1, intervals.count { it.name == "source.scan" })
    assertEquals(1, intervals.count { it.name == "source.file.summary" })
    assertTrue(intervals.none { it.name.endsWith(".result") })
    val ranked = intervals.single { it.attributes["rank"] == "1" }
    val child = intervals.single { it.name == "source.file.annotationScan" }
    assertEquals(ranked.id, child.parentId)
    val ids = intervals.map { it.id }.toSet()
    assertTrue(intervals.all { it.parentId == null || it.parentId in ids })
    assertEquals("2", timeline.overview()?.attributes?.get("dropped_events"))
  }

  fun testOrdinaryRecordsCannotConsumeAReservedParentSlot() {
    val timeline = IdeTraceTimeline(capacity = 3, enclosingReserve = 0, priorityReserve = 0)
    val parent = checkNotNull(timeline.reserveDetail(priority = true))
    timeline.record(span(2, 1, 10, 20))
    timeline.record(span(3, 1, 30, 40))
    timeline.record(span(4, 1, 50, 60))
    timeline.recordReservedDetail(parent, span(1, null, 0, 100))
    timeline.releaseDetail(parent)
    val intervals = timeline.lanes().flatMap { it.intervals }
    assertEquals(setOf(1L, 2L, 3L), intervals.map { it.id }.toSet())
    assertEquals("1", timeline.overview()?.attributes?.get("dropped_events"))
  }

  fun testUnusedReservationCanBeReleasedOnceAfterFailure() {
    val timeline = IdeTraceTimeline(capacity = 1, enclosingReserve = 0, priorityReserve = 0)
    val unused = checkNotNull(timeline.reserveDetail(priority = false))
    timeline.releaseDetail(unused)
    timeline.releaseDetail(unused)
    val next = checkNotNull(timeline.reserveDetail(priority = false))
    timeline.recordReservedDetail(next, span(1, null, 0, 100))
    assertNull(timeline.reserveDetail(priority = false))
    assertEquals(1, timeline.lanes().sumOf { it.intervals.size })
  }

  fun testMetadataFailureKeepsAlreadyRecordedChildrenAndTheirParent() {
    val timeline = IdeTraceTimeline(capacity = 4, enclosingReserve = 1, priorityReserve = 0)
    val failures = mutableListOf<Throwable>()
    var now = 0L
    val capture = IdeTraceCapture(TraceScope.noop(), { now }, { failures += it }, timeline)
    val failure = IllegalStateException("Cannot finish detail metadata")
    IdeTraceOperation(capture, "source.scan").run { operation ->
      checkNotNull(operation)
      assertTrue(
        operation.completedPhase("source.file.item", 10, 80) {
          assertTrue(completedPhase("source.file.annotationScan", 20, 30))
          throw failure
        }
      )
      assertTrue(operation.completedPhase("source.file.item", 85, 90))
      now = 100
    }
    assertEquals(listOf(failure), failures)
    val intervals = timeline.lanes().flatMap { it.intervals }
    assertEquals(4, intervals.size)
    val parent = intervals.single { it.attributes["outcome"] == "failed" }
    val child = intervals.single { it.name == "source.file.annotationScan" }
    assertEquals(parent.id, child.parentId)
  }

  fun testEveryFileAndClassItemHasASubjectLabel() {
    assertEquals(
      "Analyze file: src/AppGraph.kt",
      ideTraceDisplayName("source.file.item", mapOf("file" to "src/AppGraph.kt")),
    )
    assertEquals(
      "Resolve class: example.AppGraph",
      ideTraceDisplayName("source.class.item", mapOf("class" to "example.AppGraph")),
    )
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
