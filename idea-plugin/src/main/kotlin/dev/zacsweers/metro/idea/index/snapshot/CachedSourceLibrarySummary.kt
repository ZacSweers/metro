// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.progress.ProgressManager

/** Retains one completed class-resolution pass while later preparation or sealing is retried. */
internal class CachedSourceLibrarySummary(
  private val source: SourceSnapshot,
  private val invalidationRevision: Long,
  val summary: FinalizedSourceLibrarySummary,
) {
  /** Reuse requires the same source shards, project inputs, and live class dependencies. */
  fun matches(candidate: SourceSnapshot, revision: Long): Boolean {
    if (invalidationRevision != revision || source.inputs != candidate.inputs) return false
    if (source.moduleFingerprints != candidate.moduleFingerprints) return false
    if (source.shardOrder != candidate.shardOrder) return false
    if (!summary.sourceClasses.dependencies.isCurrent()) return false
    for (file in source.shardOrder) {
      ProgressManager.checkCanceled()
      if (source.shards[file] !== candidate.shards[file]) return false
    }
    return true
  }
}
