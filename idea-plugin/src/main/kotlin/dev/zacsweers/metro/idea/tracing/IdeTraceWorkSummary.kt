// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.tracing

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException

/** Keeps module totals and twenty slow items for one phase. Entries retain only scalar values. */
internal class IdeTraceWorkSummary(
  private val operation: IdeTraceOperation,
  private val name: String,
) {
  private class ModuleTotals {
    var items = 0
    var elapsed = 0L
    var reads = 0
    var canceledReads = 0
    var readElapsed = 0L
    var canceledReadElapsed = 0L
    val outcomes = linkedMapOf<String, Int>()
    val caches = linkedMapOf<String, Int>()

    fun add(item: IdeTraceWorkItem) {
      items++
      elapsed += item.elapsed
      reads += item.readAttempts
      canceledReads += item.canceledReadAttempts
      readElapsed += item.readElapsed
      canceledReadElapsed += item.canceledReadElapsed
      outcomes.merge(item.outcome, 1, Int::plus)
      item.cache?.let { caches.merge(it, 1, Int::plus) }
    }
  }

  private val modules = linkedMapOf<String, ModuleTotals>()
  private val slowest = PriorityQueue<IdeTraceWorkItem>(compareBy { it.elapsed })

  fun start(): IdeTraceWorkItem = IdeTraceWorkItem(operation::nowNanos)

  fun finish(item: IdeTraceWorkItem) {
    item.finish()
    modules.getOrPut(item.module, ::ModuleTotals).add(item)
    if (slowest.size < MAX_SLOW_ITEMS) {
      slowest += item
    } else if (item.elapsed > slowest.peek().elapsed) {
      slowest.remove()
      slowest += item
    }
  }

  /** Called from finally so interrupted phases include completed and canceled work. */
  fun report() {
    for ((module, totals) in modules) {
      operation.event("$name.module") {
        attribute("module", module)
        attribute("items", totals.items)
        attribute("total_elapsed_ns", totals.elapsed)
        attribute("read_attempts", totals.reads)
        attribute("canceled_read_attempts", totals.canceledReads)
        attribute("read_elapsed_ns", totals.readElapsed)
        attribute("canceled_read_elapsed_ns", totals.canceledReadElapsed)
        attribute("outside_read_ns", (totals.elapsed - totals.readElapsed).coerceAtLeast(0))
        for ((outcome, count) in totals.outcomes) attribute("outcome.$outcome.count", count)
        for ((cache, count) in totals.caches) attribute("cache.$cache.count", count)
      }
    }
    for ((index, item) in slowest.sortedByDescending { it.elapsed }.withIndex()) {
      operation.completedPhase("$name.slow", item.started, item.finished) {
        attribute("rank", index + 1)
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
      }
    }
  }

  private companion object {
    const val MAX_SLOW_ITEMS = 20
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

/** Source paths stay recognizable when a trace is shared outside its original checkout. */
internal fun ideTraceFilePath(project: Project, file: VirtualFile): String {
  val base = project.basePath ?: return file.name
  val prefix = "${base.trimEnd('/')}/"
  return if (file.path.startsWith(prefix)) file.path.removePrefix(prefix) else file.name
}
