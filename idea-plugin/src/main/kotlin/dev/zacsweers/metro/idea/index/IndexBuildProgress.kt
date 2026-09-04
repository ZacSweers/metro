// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

internal enum class IndexBuildPhase(val message: String) {
  QUEUED("Preparing Metro graphs"),
  DISCOVERING_SOURCE_FILES("Finding Metro source files"),
  ANALYZING_DECLARATIONS("Checking Metro source files"),
  COMBINING_DECLARATIONS("Combining Metro declarations"),
  RESOLVING_CLASS_BINDINGS("Resolving injected classes and objects"),
  READING_DEPENDENCY_METADATA("Reading dependency metadata"),
  BUILDING_GRAPH_INDEX("Building the Metro graph index"),
}

/** File counts include visited cache entries. Optional counters describe completed shard reads. */
internal data class IndexBuildProgress(
  val phase: IndexBuildPhase,
  val completed: Int? = null,
  val total: Int? = null,
  val reused: Int? = null,
  val rebuilt: Int? = null,
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
  }

  val message: String
    get() {
      val completed = completed ?: return phase.message
      val total = total ?: return phase.message
      val details =
        if (reused != null && rebuilt != null) ", $reused reused, $rebuilt rebuilt" else ""
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
  private var lastPhase: IndexBuildPhase? = null

  fun phase(phase: IndexBuildPhase) {
    publish(IndexBuildProgress(phase))
    lastPhase = phase
    lastPublishedAt = nanoTime()
  }

  fun counted(
    phase: IndexBuildPhase,
    completed: Int,
    total: Int,
    reused: Int? = null,
    rebuilt: Int? = null,
  ) {
    val now = nanoTime()
    val phaseChanged = phase != lastPhase
    val atBoundary = completed == 0 || completed >= total
    val intervalElapsed = lastPublishedAt?.let { now - it >= updateIntervalNanos } ?: true
    if (!phaseChanged && !atBoundary && !intervalElapsed) return

    publish(IndexBuildProgress(phase, completed, total, reused, rebuilt))
    lastPhase = phase
    lastPublishedAt = now
  }
}
