// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import dev.zacsweers.metro.compiler.tracing.TraceScope
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException

/** Verifies bounded attribution independently of IDE scheduling and real clock speed. */
class IdeTraceWorkSummaryTest : TestCase() {
  fun testOnlySlowestTwentyItemsAreEmittedAndModuleTotalsIncludeEveryItem() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.file")
      for (index in 1..40) {
        summary.measure { item ->
          checkNotNull(item)
          item.module = if (index % 2 == 0) "app" else "library"
          item.file = "src/File$index.kt"
          item.cache = if (index % 2 == 0) "rebuilt" else "reused"
          item.measureRead { clock.now += index }
        }
      }
      assertTrue(timeline.lanes().isEmpty())
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      val slow = intervals.filter { it.name == "source.file.slow" }
      assertEquals(20, slow.size)
      assertEquals(
        (21..40).map { "src/File$it.kt" }.toSet(),
        slow.map { it.attributes["file"] }.toSet(),
      )
      val first = slow.single { it.attributes["rank"] == "1" }
      assertEquals("src/File40.kt", first.attributes["file"])
      assertEquals(40L, checkNotNull(first.finished) - first.started)
      val totals =
        intervals.filter { it.name == "source.file.module" }.associateBy { it.attributes["module"] }
      assertEquals(setOf("app", "library"), totals.keys)
      assertEquals("20", totals.getValue("app").attributes["items"])
      assertEquals("420", totals.getValue("app").attributes["total_elapsed_ns"])
      assertEquals("20", totals.getValue("app").attributes["cache.rebuilt.count"])
      assertEquals("20", totals.getValue("library").attributes["cache.reused.count"])
      assertEquals("400", totals.getValue("library").attributes["total_elapsed_ns"])
    }
  }

  fun testRetriedReadKeepsCanceledTimeAndAdmissionTimeSeparate() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.file")
      summary.measure { item ->
        checkNotNull(item)
        item.file = "src/AppGraph.kt"
        try {
          item.measureRead {
            clock.now += 10
            throw CancellationException("Write action")
          }
        } catch (_: CancellationException) {
          // A later read succeeds after the original attempt yields to a write action.
        }
        clock.now += 20
        item.measureRead { clock.now += 30 }
        item.cache = "reused"
      }
      summary.report()
      val result =
        timeline
          .lanes()
          .flatMap { it.intervals }
          .single { it.name == "source.file.slow" }
          .attributes
      assertEquals("60", result["elapsed_ns"])
      assertEquals("2", result["read_attempts"])
      assertEquals("1", result["canceled_read_attempts"])
      assertEquals("40", result["read_elapsed_ns"])
      assertEquals("10", result["canceled_read_elapsed_ns"])
      assertEquals("20", result["outside_read_ns"])
      assertEquals("completed", result["outcome"])
      assertEquals("reused", result["cache"])
    }
  }

  fun testCancellationRecordsPartialWorkAndPreservesException() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.class")
      val cancellation = CancellationException("Superseded")
      try {
        summary.measure { item ->
          item?.className = "example.AppGraph"
          item.measureRead {
            clock.now += 25
            throw cancellation
          }
        }
        fail("Expected cancellation")
      } catch (actual: CancellationException) {
        assertSame(cancellation, actual)
      } finally {
        summary.report()
      }
      val result =
        timeline
          .lanes()
          .flatMap { it.intervals }
          .single { it.name == "source.class.slow" }
          .attributes
      assertEquals("canceled", result["outcome"])
      assertEquals("25", result["canceled_read_elapsed_ns"])
      assertEquals("example.AppGraph", result["class"])
    }
  }

  fun testDisabledSummarySkipsRecordsAndLabels() {
    val summary: IdeTraceWorkSummary? = null
    var calls = 0
    val result = summary.measure { item ->
      assertNull(item)
      item?.file = error("Built disabled label")
      item.measureRead {
        calls++
        42
      }
    }
    assertEquals(42, result)
    assertEquals(1, calls)
  }

  private class Clock(var now: Long = 0)

  private fun withTrace(block: (IdeTraceOperation, IdeTraceTimeline, Clock) -> Unit) {
    val clock = Clock()
    val timeline = IdeTraceTimeline()
    val capture =
      IdeTraceCapture(TraceScope.noop(), { clock.now }, { throw AssertionError(it) }, timeline)
    IdeTraceOperation(capture, "source.scan").run { operation ->
      block(checkNotNull(operation), timeline, clock)
    }
  }
}
