// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.project.Project
import dev.zacsweers.metro.compiler.parallelMapIndexed
import dev.zacsweers.metro.idea.index.IndexBuildFile
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkSummary
import dev.zacsweers.metro.idea.tracing.measure
import dev.zacsweers.metro.idea.tracing.measureRead
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs one discovery phase outside read access. Each capture has its own retry boundary; results
 * cross that boundary as detached values and are collected in input order. Captures must keep their
 * mutable state local, because an IDE write can repeat a capture before it returns.
 */
internal class SnapshotReadExecutor(
  private val project: Project,
  val parallelism: Int,
  private val progress: IndexBuildProgressReporter,
  private val phase: IndexBuildPhase,
  private val trace: IdeTraceOperation? = null,
  private val checkCurrent: () -> Unit = {},
) {
  private val slots = arrayOfNulls<IndexBuildFile>(parallelism)
  private var completed = 0
  private var scheduled = 0
  private var peakWorkers = 0
  private val work = trace?.let {
    val prefix =
      when (phase) {
        IndexBuildPhase.RESOLVING_CLASS_BINDINGS -> "source.request"
        IndexBuildPhase.RESOLVING_LIBRARY_CLASSES -> "library.request"
        else -> "metadata.request"
      }
    IdeTraceWorkSummary(it, prefix)
  }

  init {
    require(parallelism > 0)
    require(phase.discoversMoreWork)
  }

  /** The refresh ticker belongs to the phase and stops after all captures have joined. */
  suspend fun <T> run(block: suspend (SnapshotReadExecutor) -> T): T = coroutineScope {
    publish(force = true)
    val updates = launch {
      while (isActive) {
        delay(250.milliseconds)
        publish()
      }
    }
    try {
      block(this@SnapshotReadExecutor)
    } finally {
      updates.cancel()
      publish(force = true)
      trace?.attribute("workers.limit", parallelism)
      trace?.attribute("workers.peak", peakWorkers)
      trace?.attribute("requests.completed", completed)
      work?.report()
    }
  }

  /** Captures small preparation or validation results without retaining a read lock in callers. */
  suspend fun <T> read(capture: () -> T): T =
    readSnapshotStage(project, checkCurrent, trace, capture)

  /**
   * A bounded frontier may discover more work when the caller merges its results. Completed
   * captures survive another item's read retry; the caller validates their combined dependencies.
   *
   * Calls on one executor must not overlap. Each call uses the whole worker pool, and the progress
   * slots are sized for one pool.
   */
  suspend fun <T, R> map(
    items: List<T>,
    describe: (T) -> IndexBuildFile,
    capture: (T) -> R,
  ): List<R> {
    if (items.isEmpty()) {
      return emptyList()
    }
    // Resolve descriptions once so a worker can show its subject while waiting for read access.
    val descriptions = read { items.map(describe) }
    synchronized(this) {
      scheduled += items.size
      publish()
    }
    return items.parallelMapIndexed(parallelism) { index, item ->
      val slot = started(descriptions[index])
      var completedRead = false
      try {
        val value = work.measure { workItem ->
          workItem?.className = descriptions[index].name
          workItem?.file = descriptions[index].path
          workItem?.module = descriptions[index].module ?: "<unknown>"
          read {
            update(slot, describe(item))
            workItem.measureRead { capture(item) }
          }
        }
        completedRead = true
        value
      } finally {
        finished(slot, completedRead)
      }
    }
  }

  @Synchronized
  private fun started(file: IndexBuildFile): Int {
    val slot = slots.indexOfFirst { it == null }
    check(slot >= 0) { "Snapshot reads exceeded the configured pool size" }
    slots[slot] = file
    peakWorkers = maxOf(peakWorkers, slots.count { it != null })
    try {
      publish()
      return slot
    } catch (failure: Throwable) {
      // A progress consumer can cancel before map receives this slot and owns its cleanup.
      slots[slot] = null
      throw failure
    }
  }

  @Synchronized
  private fun update(slot: Int, file: IndexBuildFile) {
    slots[slot] = file
    publish()
  }

  @Synchronized
  private fun finished(slot: Int, succeeded: Boolean) {
    slots[slot] = null
    if (succeeded) {
      completed++
    }
    publish()
  }

  @Synchronized
  private fun publish(force: Boolean = false) {
    progress.counted(
      phase,
      completed,
      scheduled,
      activeWorkers = slots.count { it != null },
      workerLimit = parallelism,
      workerFiles = slots.toList(),
      force = force,
    )
  }
}
