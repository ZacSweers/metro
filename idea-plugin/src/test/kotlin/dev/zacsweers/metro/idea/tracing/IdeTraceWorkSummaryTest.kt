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
      assertEquals("10", totals.getValue("app").attributes["shown_items"])
      assertEquals("10", totals.getValue("app").attributes["omitted_items"])
      assertEquals("310", totals.getValue("app").attributes["shown_elapsed_ns"])
      assertEquals("110", totals.getValue("app").attributes["omitted_elapsed_ns"])
      val report = intervals.single { it.name == "source.file.summary" }.attributes
      assertEquals("40", report["items"])
      assertEquals("20", report["shown_items"])
      assertEquals("20", report["omitted_items"])
      assertEquals("610", report["shown_elapsed_ns"])
      assertEquals("210", report["omitted_elapsed_ns"])
      assertEquals("20 slowest files shown; 20 more files omitted", report["display_name"])
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

  fun testNestedStagesAreEmittedOnlyForRetainedItemsAndTotalsIncludeOmittedItems() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.class")
      for (index in 1..40) {
        summary.measure { item ->
          checkNotNull(item)
          item.module = "app"
          item.className = "example.Class$index"
          item.stage("source.class.bindingConstruction") {
            clock.now += index
            item.stage("source.class.dependencyExpansion") { clock.now += 2 }
          }
        }
      }
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      val retained = intervals.filter { it.name == "source.class.slow" }
      val construction = intervals.filter { it.name == "source.class.bindingConstruction" }
      val expansion = intervals.filter { it.name == "source.class.dependencyExpansion" }
      assertEquals(20, retained.size)
      assertEquals(20, construction.size)
      assertEquals(20, expansion.size)
      val slow = retained.single { it.attributes["rank"] == "1" }
      val parent = construction.single { it.parentId == slow.id }
      val child = expansion.single { it.parentId == parent.id }
      assertEquals(42L, checkNotNull(parent.finished) - parent.started)
      assertEquals(2L, checkNotNull(child.finished) - child.started)
      val totals = intervals.single { it.name == "source.class.module" }.attributes
      assertEquals("40", totals["stage.source.class.bindingConstruction.attempts"])
      assertEquals("900", totals["stage.source.class.bindingConstruction.elapsed_ns"])
      assertEquals("80", totals["stage.source.class.dependencyExpansion.elapsed_ns"])
      assertEquals("40", totals["stage_intervals_shown"])
      assertEquals("40", totals["stage_intervals_omitted"])
    }
  }

  fun testStageIntervalLimitPreservesTotalsAndReportsDroppedCost() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.file")
      summary.measure { item ->
        repeat(70) { item.stage("source.file.annotationLookup") { clock.now++ } }
      }
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      assertEquals(64, intervals.count { it.name == "source.file.annotationLookup" })
      val totals = intervals.single { it.name == "source.file.summary" }.attributes
      assertEquals("70", totals["stage.source.file.annotationLookup.attempts"])
      assertEquals("70", totals["stage.source.file.annotationLookup.elapsed_ns"])
      assertEquals("64", totals["stage_intervals_shown"])
      assertEquals("6", totals["stage_intervals_omitted"])
      assertEquals("64", totals["shown_stage_elapsed_ns"])
      assertEquals("6", totals["omitted_stage_elapsed_ns"])
      val item = intervals.single { it.name == "source.file.slow" }.attributes
      assertEquals("6", item["stage_intervals_omitted"])
      assertEquals("6", item["omitted_stage_elapsed_ns"])
      assertEquals("inclusive_wall", item["stage_timing"])
    }
  }

  fun testStageCancellationPreservesExceptionAndRecordsInclusiveCanceledCost() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.file")
      val cancellation = CancellationException("Write action")
      try {
        summary.measure { item ->
          item.stage("source.file.declarationExtraction") {
            clock.now += 5
            item.stage("source.file.annotationLookup") {
              clock.now += 10
              throw cancellation
            }
          }
        }
        fail("Expected cancellation")
      } catch (actual: CancellationException) {
        assertSame(cancellation, actual)
      }
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      val totals = intervals.single { it.name == "source.file.summary" }.attributes
      assertEquals("1", totals["stage.source.file.declarationExtraction.canceled_attempts"])
      assertEquals("15", totals["stage.source.file.declarationExtraction.canceled_elapsed_ns"])
      assertEquals("10", totals["stage.source.file.annotationLookup.canceled_elapsed_ns"])
      assertTrue(
        intervals
          .filter {
            it.name == "source.file.declarationExtraction" ||
              it.name == "source.file.annotationLookup"
          }
          .all { it.attributes["outcome"] == "canceled" }
      )
    }
  }

  fun testEntryTokenEndsOnceBeforeAnalysisWorkAndFailureIsPreserved() {
    withTrace { operation, timeline, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.class")
      val failure = IllegalStateException("Cannot resolve")
      try {
        summary.measure { item ->
          val entry = item?.beginStage("source.class.analysisEntry")
          try {
            clock.now += 5
            entry?.finish()
            item.stage("source.class.findClass") {
              clock.now += 10
              throw failure
            }
          } finally {
            entry?.finish(failure)
          }
        }
        fail("Expected failure")
      } catch (actual: IllegalStateException) {
        assertSame(failure, actual)
      }
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      val totals = intervals.single { it.name == "source.class.summary" }.attributes
      assertEquals("1", totals["stage.source.class.analysisEntry.attempts"])
      assertEquals("5", totals["stage.source.class.analysisEntry.elapsed_ns"])
      assertEquals("0", totals["stage.source.class.analysisEntry.failed_attempts"])
      assertEquals("1", totals["stage.source.class.findClass.failed_attempts"])
      assertEquals("10", totals["stage.source.class.findClass.failed_elapsed_ns"])
    }
  }

  fun testDisabledStageAllowsSingleInitializationAndNonlocalReturn() {
    val item: IdeTraceWorkItem? = null
    val result: Int
    item.stage("disabled") { result = 42 }
    assertEquals(42, result)
    var calls = 0
    fun calculate(): Int {
      item.stage("disabled") {
        calls++
        return 43
      }
    }
    assertEquals(43, calculate())
    assertEquals(1, calls)
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
