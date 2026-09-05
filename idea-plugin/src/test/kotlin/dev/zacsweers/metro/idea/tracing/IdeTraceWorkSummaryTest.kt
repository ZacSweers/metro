// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import dev.zacsweers.metro.compiler.tracing.TraceScope
import junit.framework.TestCase
import kotlinx.coroutines.CancellationException

/** Verifies bounded attribution independently of IDE scheduling and real clock speed. */
class IdeTraceWorkSummaryTest : TestCase() {
  fun testEveryItemHasOneBarAndOnlySlowestTwentyAreRanked() {
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
      summary.report()
      val intervals = timeline.lanes().flatMap { it.intervals }
      val items = intervals.filter { it.name == "source.file.item" }
      assertEquals(40, items.size)
      assertEquals(
        (1..40).map { "src/File$it.kt" }.toSet(),
        items.map { it.attributes["file"] }.toSet(),
      )
      val ranked = items.filter { "rank" in it.attributes }
      assertEquals(20, ranked.size)
      assertEquals(
        (21..40).map { "src/File$it.kt" }.toSet(),
        ranked.map { it.attributes["file"] }.toSet(),
      )
      val first = ranked.single { it.attributes["rank"] == "1" }
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
      assertEquals("20", totals.getValue("app").attributes["shown_items"])
      assertEquals("0", totals.getValue("app").attributes["omitted_items"])
      assertEquals("420", totals.getValue("app").attributes["shown_elapsed_ns"])
      assertEquals("0", totals.getValue("app").attributes["omitted_elapsed_ns"])
      val report = intervals.single { it.name == "source.file.summary" }.attributes
      assertEquals("40", report["items"])
      assertEquals("40", report["shown_items"])
      assertEquals("0", report["omitted_items"])
      assertEquals("820", report["shown_elapsed_ns"])
      assertEquals("0", report["omitted_elapsed_ns"])
      assertEquals("0", report["detailed_items"])
      assertEquals("40 files shown", report["display_name"])
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
          .single { it.name == "source.file.item" }
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
          .single { it.name == "source.class.item" }
          .attributes
      assertEquals("canceled", result["outcome"])
      assertEquals("25", result["canceled_read_elapsed_ns"])
      assertEquals("example.AppGraph", result["class"])
      assertEquals("1", result["rank"])
      val report =
        timeline
          .lanes()
          .flatMap { it.intervals }
          .single { it.name == "source.class.summary" }
          .attributes
      assertEquals("1", report["shown_items"])
      assertEquals("0", report["omitted_items"])
      assertTrue(checkNotNull(report["display_name"]).contains("class request"))
    }
  }

  fun testEveryItemHasABarAndOnlySlowestTwentyHaveNestedStages() {
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
      val retained = intervals.filter { it.name == "source.class.item" }
      val construction = intervals.filter { it.name == "source.class.bindingConstruction" }
      val expansion = intervals.filter { it.name == "source.class.dependencyExpansion" }
      assertEquals(40, retained.size)
      assertEquals(20, retained.count { "rank" in it.attributes })
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
      val report = intervals.single { it.name == "source.class.summary" }.attributes
      assertEquals("40", report["shown_items"])
      assertEquals("0", report["omitted_items"])
      assertEquals("20", report["detailed_items"])
      assertEquals("40 class requests shown; 20 with stage details", report["display_name"])
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
      val item = intervals.single { it.name == "source.file.item" }.attributes
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

  fun testCaptureBudgetKeepsLateSlowItemsAndReportsActualOmissions() {
    val timeline = IdeTraceTimeline(capacity = 12, enclosingReserve = 4, priorityReserve = 4)
    withTrace(timeline) { operation, _, clock ->
      val summary = IdeTraceWorkSummary(operation, "source.file")
      for (index in 1..35) {
        summary.measure { item ->
          checkNotNull(item)
          item.module = "app"
          item.file = "src/File$index.kt"
          item.stage("source.file.declarationExtraction") {
            clock.now += index
            item.stage("source.file.annotationLookup") { clock.now++ }
          }
        }
      }
      summary.report()
    }
    val intervals = timeline.lanes().flatMap { it.intervals }
    assertTrue(intervals.size <= 12)
    val items = intervals.filter { it.name == "source.file.item" }
    assertTrue(items.size < 35)
    assertEquals("src/File35.kt", items.single { it.attributes["rank"] == "1" }.attributes["file"])
    assertEquals(items.size, items.map { it.attributes["file"] }.toSet().size)
    val summary = intervals.single { it.name == "source.file.summary" }.attributes
    val module = intervals.single { it.name == "source.file.module" }.attributes
    val phase = intervals.single { it.name == "source.scan" }.attributes
    val shownElapsed = items.sumOf { checkNotNull(it.finished) - it.started }
    assertEquals("35", summary["items"])
    assertEquals("665", summary["total_elapsed_ns"])
    assertEquals(items.size.toString(), summary["shown_items"])
    assertEquals((35 - items.size).toString(), summary["omitted_items"])
    assertEquals(shownElapsed.toString(), summary["shown_elapsed_ns"])
    assertEquals((665 - shownElapsed).toString(), summary["omitted_elapsed_ns"])
    assertEquals(summary["omitted_items"], module["omitted_items"])
    assertEquals(summary["omitted_elapsed_ns"], module["omitted_elapsed_ns"])
    assertEquals(summary["shown_items"], phase["source.file.shown_items"])
    assertEquals(summary["omitted_elapsed_ns"], phase["source.file.omitted_elapsed_ns"])
    assertTrue(
      checkNotNull(summary["display_name"]).contains("${35 - items.size} omitted by capture limit")
    )

    val stages = intervals.filter {
      it.name == "source.file.declarationExtraction" || it.name == "source.file.annotationLookup"
    }
    assertTrue(stages.isNotEmpty())
    val ids = intervals.mapTo(mutableSetOf()) { it.id }
    assertTrue(stages.all { it.parentId in ids })
    val detailed = items.count { item -> stages.any { it.parentId == item.id } }
    assertEquals(detailed.toString(), summary["detailed_items"])
    assertEquals(stages.size.toString(), summary["stage_intervals_shown"])
    assertEquals((70 - stages.size).toString(), summary["stage_intervals_omitted"])
    val shownStageElapsed = stages.sumOf { checkNotNull(it.finished) - it.started }
    assertEquals((700 - shownStageElapsed).toString(), summary["omitted_stage_elapsed_ns"])
  }

  fun testSiblingSummariesShareTheCaptureBudgetAndKeepTheirTotals() {
    val timeline = IdeTraceTimeline(capacity = 18, enclosingReserve = 6, priorityReserve = 6)
    withTrace(timeline) { operation, _, clock ->
      val files = IdeTraceWorkSummary(operation, "source.file")
      for (index in 1..35) {
        files.measure { item ->
          checkNotNull(item)
          item.module = "app"
          item.file = "src/File$index.kt"
          item.stage("source.file.annotationLookup") { clock.now += index }
        }
      }
      files.report()
      val classes = IdeTraceWorkSummary(operation, "source.class")
      repeat(5) { index ->
        classes.measure { item ->
          checkNotNull(item)
          item.module = "app"
          item.className = "example.Class$index"
          item.stage("source.class.findClass") { clock.now += 1000 }
        }
      }
      classes.report()
    }
    val intervals = timeline.lanes().flatMap { it.intervals }
    assertTrue(intervals.size <= 18)
    assertEquals(1, intervals.count { it.name == "source.scan" })
    for ((name, totalItems, totalElapsed) in
      listOf(
        Triple("source.file", 35, 630L),
        Triple("source.class", 5, 5000L),
      )) {
      val items = intervals.filter { it.name == "$name.item" }
      val summary = intervals.single { it.name == "$name.summary" }.attributes
      val module = intervals.single { it.name == "$name.module" }.attributes
      val shownElapsed = items.sumOf { checkNotNull(it.finished) - it.started }
      assertEquals(totalItems.toString(), summary["items"])
      assertEquals(totalElapsed.toString(), summary["total_elapsed_ns"])
      assertEquals(items.size.toString(), summary["shown_items"])
      assertEquals((totalItems - items.size).toString(), summary["omitted_items"])
      assertEquals((totalElapsed - shownElapsed).toString(), summary["omitted_elapsed_ns"])
      assertEquals(summary["omitted_items"], module["omitted_items"])
      assertEquals(summary["omitted_elapsed_ns"], module["omitted_elapsed_ns"])
    }
    val classSummary = intervals.single { it.name == "source.class.summary" }.attributes
    assertTrue(checkNotNull(classSummary["display_name"]).contains("class requests shown"))
    val ids = intervals.mapTo(mutableSetOf()) { it.id }
    assertTrue(intervals.filter { it.parentId != null }.all { it.parentId in ids })
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

  private fun withTrace(
    timeline: IdeTraceTimeline = IdeTraceTimeline(),
    block: (IdeTraceOperation, IdeTraceTimeline, Clock) -> Unit,
  ) {
    val clock = Clock()
    val capture =
      IdeTraceCapture(TraceScope.noop(), { clock.now }, { throw AssertionError(it) }, timeline)
    IdeTraceOperation(capture, "source.scan").run { operation ->
      block(checkNotNull(operation), timeline, clock)
    }
  }
}
