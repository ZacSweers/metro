// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import org.jetbrains.kotlin.psi.KtFile

/**
 * Keeps successful file reads with one preparation attempt. Each read can yield to a write action
 * without discarding the discovered files, completed shards, or progress from earlier reads.
 */
internal class SourceSnapshotScanner(
  private val project: Project,
  private val fileShards: SourceFileShardCache,
  private val onShardRead: (KtFile, FileShard) -> Unit,
  private val containsRelevantAnnotation: (KtFile, Set<String>) -> Boolean,
) {
  suspend fun scan(
    previous: SourceSnapshot?,
    files: Collection<VirtualFile>,
    inputs: IndexInputs,
    moduleFingerprints: Map<Module, IndexOptionsFingerprint>,
    shortNames: Set<String>,
    pending: SourceSnapshotChanges,
    progress: IndexBuildProgressReporter,
    checkCurrent: () -> Unit,
  ): SourceSnapshot {
    val transaction = SourceSnapshotTransaction(previous)
    val scan = SourceScanProgress(progress, files.size + pending.requested.size)
    for (virtualFile in files) {
      val result =
        readSnapshotStage(project, checkCurrent) {
          readShard(virtualFile, pending, shortNames, checkAnnotations = previous != null)
        }
      if (result == null) transaction.removeShard(virtualFile)
      else transaction.applyShard(virtualFile, result.shard)
      scan.advance(result)
    }
    // Stub loading can surface requested files before their annotations reach the stub index.
    // Draining them here keeps them from lingering until another cold sweep.
    for (virtualFile in pending.requested) {
      if (transaction.containsShard(virtualFile)) {
        scan.advance(null)
        continue
      }
      val result =
        readSnapshotStage(project, checkCurrent) {
          readShard(virtualFile, pending, shortNames, checkAnnotations = true)
        }
      if (result != null) transaction.applyShard(virtualFile, result.shard)
      scan.advance(result)
    }
    return readSnapshotStage(project, checkCurrent) {
      transaction.snapshot(
        inputs,
        moduleFingerprints,
        shortNames,
        sourceModulesMayHaveChanged = pending.sourceModulesMayHaveChanged,
      )
    }
  }

  private fun readShard(
    virtualFile: VirtualFile,
    pending: SourceSnapshotChanges,
    shortNames: Set<String>,
    checkAnnotations: Boolean,
  ): SourceFileShardCache.ReadResult? {
    if (!virtualFile.isValid) return null
    val file = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: return null
    if (!file.isValid) return null
    if (checkAnnotations && !containsRelevantAnnotation(file, shortNames)) return null
    val revision = if (pending.forcesRebuild(virtualFile)) pending.invalidationRevision else null
    val result = fileShards.read(file, revision)
    onShardRead(file, result.shard)
    return result
  }
}

/** Counts successful reads separately from visited files, including skipped or removed files. */
private class SourceScanProgress(
  private val reporter: IndexBuildProgressReporter,
  private val total: Int,
) {
  private var completed = 0
  private var reused = 0
  private var rebuilt = 0

  init {
    report()
  }

  fun advance(result: SourceFileShardCache.ReadResult?) {
    if (result != null) {
      if (result.rebuilt) rebuilt++ else reused++
    }
    completed++
    report()
  }

  private fun report() {
    reporter.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, completed, total, reused, rebuilt)
  }
}
