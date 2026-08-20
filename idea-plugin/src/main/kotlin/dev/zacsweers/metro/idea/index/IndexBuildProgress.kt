// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

internal enum class IndexBuildPhase(val message: String) {
  QUEUED("Preparing Metro graphs"),
  DISCOVERING_SOURCE_FILES("Finding Metro source files"),
  ANALYZING_DECLARATIONS("Analyzing Metro declarations"),
  COMBINING_DECLARATIONS("Combining Metro declarations"),
  RESOLVING_ASSISTED_FACTORIES("Resolving assisted factories"),
  READING_DEPENDENCY_METADATA("Reading dependency metadata"),
  BUILDING_GRAPH_INDEX("Building the Metro graph index"),
}

internal data class IndexBuildProgress(
  val phase: IndexBuildPhase,
  val completed: Int? = null,
  val total: Int? = null,
) {
  init {
    require((completed == null) == (total == null))
    require(completed == null || completed >= 0)
    require(total == null || total >= 0)
    require(completed == null || total == null || completed <= total)
  }

  val message: String
    get() {
      val completed = completed ?: return phase.message
      val total = total ?: return phase.message
      return "${phase.message} ($completed of $total files)"
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

  fun counted(phase: IndexBuildPhase, completed: Int, total: Int) {
    val now = nanoTime()
    val phaseChanged = phase != lastPhase
    val atBoundary = completed == 0 || completed >= total
    val intervalElapsed = lastPublishedAt?.let { now - it >= updateIntervalNanos } ?: true
    if (!phaseChanged && !atBoundary && !intervalElapsed) return

    publish(IndexBuildProgress(phase, completed, total))
    lastPhase = phase
    lastPublishedAt = now
  }
}
