// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.PriorityQueue
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.coroutines.CancellationException

/** Emits item intervals within the capture budget and retains detailed stages for twenty items. */
internal class IdeTraceWorkSummary(
  private val operation: IdeTraceOperation,
  private val name: String,
) {
  private class WorkTotals {
    var items = 0
    var elapsed = 0L
    var reads = 0
    var canceledReads = 0
    var readElapsed = 0L
    var canceledReadElapsed = 0L
    var shownItems = 0
    var shownElapsed = 0L
    var detailedItems = 0
    var shownStages = 0
    var shownStageElapsed = 0L
    val outcomes = linkedMapOf<String, Int>()
    val caches = linkedMapOf<String, Int>()
    val stages = linkedMapOf<String, IdeTraceStageTotals>()

    fun add(item: IdeTraceWorkItem) {
      items++
      elapsed += item.elapsed
      reads += item.readAttempts
      canceledReads += item.canceledReadAttempts
      readElapsed += item.readElapsed
      canceledReadElapsed += item.canceledReadElapsed
      outcomes.merge(item.outcome, 1, Int::plus)
      item.cache?.let { caches.merge(it, 1, Int::plus) }
      for ((name, stage) in item.stageTotals) stages
        .getOrPut(name, ::IdeTraceStageTotals)
        .add(stage)
    }

    fun includeShown(item: IdeTraceWorkItem, stages: ShownStages) {
      shownItems++
      shownElapsed += item.elapsed
      if (stages.count > 0) detailedItems++
      shownStages += stages.count
      shownStageElapsed += stages.elapsed
    }
  }

  /** Counts admitted stage intervals; elapsed time includes nested stages. */
  private data class ShownStages(val count: Int = 0, val elapsed: Long = 0L) {
    operator fun plus(other: ShownStages) =
      ShownStages(count + other.count, elapsed + other.elapsed)
  }

  private val totals = WorkTotals()
  private val modules = linkedMapOf<String, WorkTotals>()
  private val slowest = PriorityQueue<IdeTraceWorkItem>(compareBy { it.elapsed })

  fun start(): IdeTraceWorkItem = IdeTraceWorkItem(operation::nowNanos)

  fun finish(item: IdeTraceWorkItem) {
    item.finish()
    totals.add(item)
    modules.getOrPut(item.module, ::WorkTotals).add(item)
    if (slowest.size < MAX_SLOW_ITEMS) {
      slowest += item
    } else if (item.elapsed > slowest.peek().elapsed) {
      emitItem(slowest.remove())
      slowest += item
    } else {
      emitItem(item)
    }
  }

  /** Called from finally so interrupted phases include completed and canceled work. */
  fun report() {
    for ((index, item) in slowest.sortedByDescending { it.elapsed }.withIndex()) {
      emitItem(item, rank = index + 1)
    }
    slowest.clear()
    operation.writeTotals(totals, prefix = "$name.")
    // Resolve the item kind before entering the event receiver, which has its own name.
    val noun = if (name.endsWith(".class")) "class requests" else "files"
    operation.event("$name.summary") {
      writeTotals(totals)
      val omitted = totals.items - totals.shownItems
      val description = buildString {
        append("${totals.shownItems} $noun shown")
        if (totals.detailedItems > 0) append("; ${totals.detailedItems} with stage details")
        if (omitted > 0) append("; $omitted omitted by capture limit")
      }
      attribute("display_name", description)
    }
    for ((module, moduleTotals) in modules) {
      operation.event("$name.module") {
        attribute("module", module)
        writeTotals(moduleTotals)
      }
    }
  }

  /** Evicted items are emitted immediately so only the twenty candidates retain stage trees. */
  private fun emitItem(item: IdeTraceWorkItem, rank: Int? = null) {
    var shownStages = ShownStages()
    val emitted =
      operation.completedPhase("$name.item", item.started, item.finished, priority = rank != null) {
        rank?.let { attribute("rank", it) }
        attribute("module", item.module)
        item.file?.let { attribute("file", it) }
        item.className?.let { attribute("class", it) }
        item.cache?.let { attribute("cache", it) }
        outcome(item.outcome)
        attribute("read_attempts", item.readAttempts)
        attribute("canceled_read_attempts", item.canceledReadAttempts)
        attribute("read_elapsed_ns", item.readElapsed)
        attribute("canceled_read_elapsed_ns", item.canceledReadElapsed)
        attribute("outside_read_ns", (item.elapsed - item.readElapsed).coerceAtLeast(0))
        if (rank != null) {
          writeStageTotals(item.stageTotals)
          shownStages = emitStages(item.stages.groupBy { it.parentId }, parentId = null)
        }
        val stageCount = item.stageTotals.values.sumOf { it.attempts }
        attribute("stage_intervals_shown", shownStages.count)
        attribute("stage_intervals_omitted", stageCount - shownStages.count)
        val stageElapsed = item.stageTotals.values.sumOf { it.elapsed }
        attribute("stage_timing", "inclusive_wall")
        attribute("stage_elapsed_ns", stageElapsed)
        attribute("shown_stage_elapsed_ns", shownStages.elapsed)
        attribute("omitted_stage_elapsed_ns", stageElapsed - shownStages.elapsed)
      }
    if (emitted) {
      totals.includeShown(item, shownStages)
      modules.getValue(item.module).includeShown(item, shownStages)
    }
  }

  /** Inclusive stage times can overlap when a stage contains other stages. */
  private fun IdeTraceOperation.writeTotals(
    totals: WorkTotals,
    prefix: String = "",
  ) {
    val stageCount = totals.stages.values.sumOf { it.attempts }
    val stageElapsed = totals.stages.values.sumOf { it.elapsed }
    attribute("${prefix}items", totals.items)
    attribute("${prefix}shown_items", totals.shownItems)
    attribute("${prefix}omitted_items", totals.items - totals.shownItems)
    attribute("${prefix}detailed_items", totals.detailedItems)
    attribute("${prefix}total_elapsed_ns", totals.elapsed)
    attribute("${prefix}shown_elapsed_ns", totals.shownElapsed)
    attribute("${prefix}omitted_elapsed_ns", totals.elapsed - totals.shownElapsed)
    attribute("${prefix}read_attempts", totals.reads)
    attribute("${prefix}canceled_read_attempts", totals.canceledReads)
    attribute("${prefix}read_elapsed_ns", totals.readElapsed)
    attribute("${prefix}canceled_read_elapsed_ns", totals.canceledReadElapsed)
    attribute("${prefix}outside_read_ns", (totals.elapsed - totals.readElapsed).coerceAtLeast(0))
    attribute("${prefix}stage_timing", "inclusive_wall")
    attribute("${prefix}stage_intervals_shown", totals.shownStages)
    attribute("${prefix}stage_intervals_omitted", stageCount - totals.shownStages)
    attribute("${prefix}stage_elapsed_ns", stageElapsed)
    attribute("${prefix}shown_stage_elapsed_ns", totals.shownStageElapsed)
    attribute("${prefix}omitted_stage_elapsed_ns", stageElapsed - totals.shownStageElapsed)
    for ((outcome, count) in totals.outcomes) attribute("${prefix}outcome.$outcome.count", count)
    for ((cache, count) in totals.caches) attribute("${prefix}cache.$cache.count", count)
    writeStageTotals(totals.stages)
  }

  private fun IdeTraceOperation.writeStageTotals(totals: Map<String, IdeTraceStageTotals>) {
    for ((name, stage) in totals) {
      val key = "stage.$name"
      attribute("$key.attempts", stage.attempts)
      attribute("$key.elapsed_ns", stage.elapsed)
      attribute("$key.canceled_attempts", stage.canceledAttempts)
      attribute("$key.canceled_elapsed_ns", stage.canceledElapsed)
      attribute("$key.failed_attempts", stage.failedAttempts)
      attribute("$key.failed_elapsed_ns", stage.failedElapsed)
    }
  }

  /** Retained stages share the slow item's context and retain their original nesting. */
  private fun IdeTraceOperation.emitStages(
    children: Map<Int?, List<IdeTraceWorkStage>>,
    parentId: Int?,
  ): ShownStages {
    var shown = ShownStages()
    for (stage in children[parentId].orEmpty()) {
      var descendants = ShownStages()
      val emitted =
        completedPhase(stage.name, stage.started, stage.finished, priority = true) {
          attribute("stage_sequence", stage.id)
          outcome(stage.outcome)
          descendants = emitStages(children, stage.id)
        }
      if (emitted) shown += ShownStages(1, stage.elapsed) + descendants
    }
    return shown
  }

  private companion object {
    const val MAX_SLOW_ITEMS = 20
  }
}

/** All stage attempts contribute to totals, including intervals omitted from the trace. */
internal class IdeTraceStageTotals {
  var attempts = 0
  var elapsed = 0L
  var canceledAttempts = 0
  var canceledElapsed = 0L
  var failedAttempts = 0
  var failedElapsed = 0L

  fun add(other: IdeTraceStageTotals) {
    attempts += other.attempts
    elapsed += other.elapsed
    canceledAttempts += other.canceledAttempts
    canceledElapsed += other.canceledElapsed
    failedAttempts += other.failedAttempts
    failedElapsed += other.failedElapsed
  }

  fun record(duration: Long, outcome: String) {
    attempts++
    elapsed += duration
    if (outcome == "canceled") {
      canceledAttempts++
      canceledElapsed += duration
    } else if (outcome == "failed") {
      failedAttempts++
      failedElapsed += duration
    }
  }
}

/** Immutable interval retained only while its file or class can be among the slowest items. */
internal data class IdeTraceWorkStage(
  val id: Int,
  val parentId: Int?,
  val name: String,
  val started: Long,
  val finished: Long,
  val outcome: String,
) {
  val elapsed: Long
    get() = finished - started
}

/** Idempotent completion supports measuring setup until an analysis callback begins. */
internal class IdeTraceStageToken
internal constructor(
  internal val name: String,
  internal val started: Long,
  internal val id: Int?,
  internal val parentId: Int?,
  private val onFinish: (IdeTraceStageToken, Throwable?) -> Unit,
) {
  private var finished = false

  fun finish(failure: Throwable? = null) {
    if (finished) return
    finished = true
    onFinish(this, failure)
  }
}

/** Measures wall time inside read callbacks separately from the surrounding request. */
internal class IdeTraceWorkItem(private val nanoTime: () -> Long) {
  val started = nanoTime()
  var finished = started
    private set

  val elapsed: Long
    get() = finished - started

  var module = "<unknown>"
  var file: String? = null
  var className: String? = null
  var cache: String? = null
  var outcome = "completed"
  var readAttempts = 0
    private set

  var canceledReadAttempts = 0
    private set

  var readElapsed = 0L
    private set

  var canceledReadElapsed = 0L
    private set

  internal val stageTotals = linkedMapOf<String, IdeTraceStageTotals>()
  internal val stages = mutableListOf<IdeTraceWorkStage>()
  private val activeStages = mutableListOf<IdeTraceStageToken>()
  private var retainedStageCount = 0

  /** Reserves parent IDs on entry so nested stages remain ordered when they finish. */
  fun beginStage(name: String): IdeTraceStageToken {
    val id = if (retainedStageCount < MAX_STAGES) ++retainedStageCount else null
    val parent = activeStages.lastOrNull { it.id != null }?.id
    val token = IdeTraceStageToken(name, nanoTime(), id, parent, ::finishStage)
    activeStages += token
    return token
  }

  private fun finishStage(token: IdeTraceStageToken, failure: Throwable?) {
    val finished = nanoTime()
    val outcome =
      when {
        failure == null -> "completed"
        isCancellation(failure) -> "canceled"
        else -> "failed"
      }
    stageTotals
      .getOrPut(token.name, ::IdeTraceStageTotals)
      .record(finished - token.started, outcome)
    activeStages.remove(token)
    token.id?.let { id ->
      stages += IdeTraceWorkStage(id, token.parentId, token.name, token.started, finished, outcome)
    }
  }

  fun <T> read(block: () -> T): T {
    val start = nanoTime()
    var canceled = false
    try {
      return block()
    } catch (failure: Throwable) {
      canceled = isCancellation(failure)
      throw failure
    } finally {
      val duration = nanoTime() - start
      readAttempts++
      readElapsed += duration
      if (canceled) {
        canceledReadAttempts++
        canceledReadElapsed += duration
      }
    }
  }

  fun failed(failure: Throwable) {
    outcome = if (isCancellation(failure)) "canceled" else "failed"
  }

  fun finish() {
    finished = nanoTime()
  }

  private fun isCancellation(failure: Throwable) =
    failure is CancellationException || failure is ProcessCanceledException

  private companion object {
    const val MAX_STAGES = 64
  }
}

/** Disabled tracing executes work directly and avoids clocks, records, and file labels. */
internal inline fun <T> IdeTraceWorkSummary?.measure(block: (IdeTraceWorkItem?) -> T): T {
  if (this == null) return block(null)
  val item = start()
  try {
    return block(item)
  } catch (failure: Throwable) {
    item.failed(failure)
    throw failure
  } finally {
    finish(item)
  }
}

internal inline fun <T> IdeTraceWorkItem?.measureRead(crossinline block: () -> T): T =
  if (this == null) block() else read { block() }

/** Disabled stages execute once without timing or retaining any stage state. */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> IdeTraceWorkItem?.stage(name: String, block: () -> T): T {
  contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
  if (this == null) return block()
  val token = beginStage(name)
  var failure: Throwable? = null
  try {
    return block()
  } catch (caught: Throwable) {
    failure = caught
    throw caught
  } finally {
    token.finish(failure)
  }
}

/** Source paths stay recognizable when a trace is shared outside its original checkout. */
internal fun ideTraceFilePath(project: Project, file: VirtualFile): String {
  val base = project.basePath ?: return file.name
  val prefix = "${base.trimEnd('/')}/"
  return if (file.path.startsWith(prefix)) file.path.removePrefix(prefix) else file.name
}
