// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

internal enum class IndexBuildPhase(
  val message: String,
  val unit: String = "files",
  val discoversMoreWork: Boolean = false,
) {
  QUEUED("Preparing Metro graphs"),
  DISCOVERING_SOURCE_FILES("Finding Metro source files"),
  ANALYZING_DECLARATIONS("Checking Metro source files"),
  COMBINING_DECLARATIONS("Combining Metro declarations"),
  RESOLVING_CLASS_BINDINGS("Resolving injected classes and objects", "classes", true),
  READING_DEPENDENCY_METADATA("Reading dependency metadata", "items", true),
  RESOLVING_LIBRARY_CLASSES("Resolving library classes", "classes", true),
  BUILDING_GRAPH_INDEX("Building the Metro graph index"),
}

/** Detached file, class, or hint display data; progress never retains PSI or module objects. */
internal data class IndexBuildFile(val name: String, val path: String, val module: String? = null)

/**
 * File counts include visited cache entries. Worker slots include file tasks waiting for IDE reads
 * and remain assigned through retries. Each update owns a snapshot of the slots; null entries are
 * idle. The scan serializes updates and counts each completed file once.
 */
internal data class IndexBuildProgress(
  val phase: IndexBuildPhase,
  val completed: Int? = null,
  val total: Int? = null,
  val reused: Int? = null,
  val rebuilt: Int? = null,
  val activeWorkers: Int? = null,
  val workerLimit: Int? = null,
  val workerFiles: List<IndexBuildFile?> = emptyList(),
) {
  init {
    require((completed == null) == (total == null))
    require(completed == null || completed >= 0)
    require(total == null || total >= 0)
    require(completed == null || total == null || completed <= total)
    require((reused == null) == (rebuilt == null))
    if (reused != null && rebuilt != null) {
      require(completed != null)
      require(reused >= 0 && rebuilt >= 0)
      require(reused.toLong() + rebuilt <= completed)
    }
    require((activeWorkers == null) == (workerLimit == null))
    if (activeWorkers != null && workerLimit != null) {
      require(phase == IndexBuildPhase.ANALYZING_DECLARATIONS || phase.discoversMoreWork)
      require(completed != null)
      require(workerLimit > 0)
      require(activeWorkers in 0..workerLimit)
    }
    if (workerFiles.isNotEmpty()) {
      require(workerFiles.size == workerLimit)
      require(workerFiles.count { it != null } == activeWorkers)
    }
  }

  val message: String
    get() {
      val completed = completed ?: return phase.message
      val total = total ?: return phase.message
      if (phase.discoversMoreWork) {
        return "${phase.message} ($completed ${phase.unit} checked)"
      }
      val details =
        if (reused != null && rebuilt != null) {
          ", $reused reused, $rebuilt rebuilt"
        } else {
          ""
        }
      return "${phase.message} ($completed of $total files$details)"
    }
}

/** Limits progress notifications while preserving stage changes and count boundaries. */
internal class IndexBuildProgressReporter(
  private val publish: (IndexBuildProgress) -> Unit,
  private val updateIntervalNanos: Long = 250_000_000L,
  private val nanoTime: () -> Long = System::nanoTime,
) {
  private var lastPublishedAt: Long? = null
  private var lastProgress: IndexBuildProgress? = null

  fun phase(phase: IndexBuildPhase) {
    val progress = IndexBuildProgress(phase)
    publish(progress)
    lastProgress = progress
    lastPublishedAt = nanoTime()
  }

  /** Terminal updates can bypass throttling to clear file rows after cancellation. */
  fun counted(
    phase: IndexBuildPhase,
    completed: Int,
    total: Int,
    reused: Int? = null,
    rebuilt: Int? = null,
    activeWorkers: Int? = null,
    workerLimit: Int? = null,
    workerFiles: List<IndexBuildFile?> = emptyList(),
    force: Boolean = false,
  ) {
    val progress =
      IndexBuildProgress(
        phase,
        completed,
        total,
        reused,
        rebuilt,
        activeWorkers,
        workerLimit,
        workerFiles,
      )
    val previous = lastProgress
    if (!force && progress == previous) {
      return
    }
    val now = nanoTime()
    val phaseChanged = phase != previous?.phase
    val countChanged = completed != previous?.completed || total != previous?.total
    // Discovery totals grow after each batch. Only fixed totals mark the end of a phase;
    // publishing every discovery batch's empty slots would hide the next batch's activity.
    val fixedTotalComplete = !phase.discoversMoreWork && completed >= total
    val atBoundary = (completed == 0 || fixedTotalComplete) && countChanged
    // Show the full initial pool before a long first read and clear the last occupied worker
    // promptly.
    val initialPoolFilled =
      completed == 0 && workerLimit != null && activeWorkers == minOf(workerLimit, total)
    val workersDrained = fixedTotalComplete && activeWorkers == 0
    val intervalElapsed = lastPublishedAt?.let { now - it >= updateIntervalNanos } ?: true
    val activityBoundary = initialPoolFilled || workersDrained
    val publishImmediately = force || phaseChanged || atBoundary || activityBoundary
    if (!publishImmediately && !intervalElapsed) {
      return
    }

    publish(progress)
    lastProgress = progress
    lastPublishedAt = now
  }
}
