// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkSummary
import dev.zacsweers.metro.idea.tracing.ideTraceFilePath
import dev.zacsweers.metro.idea.tracing.measure
import dev.zacsweers.metro.idea.tracing.measureRead
import dev.zacsweers.metro.idea.tracing.stage
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
    trace: IdeTraceOperation? = null,
    checkCurrent: () -> Unit,
  ): SourceSnapshot {
    val transaction = SourceSnapshotTransaction(previous)
    val scan = SourceScanProgress(progress, files.size + pending.requested.size)
    val work = trace?.let { IdeTraceWorkSummary(it, "source.file") }
    try {
      for (virtualFile in files) {
        val result =
          readShardStage(
            virtualFile,
            pending,
            shortNames,
            previous != null,
            trace,
            work,
            checkCurrent,
          )
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
          readShardStage(virtualFile, pending, shortNames, true, trace, work, checkCurrent)
        if (result != null) transaction.applyShard(virtualFile, result.shard)
        scan.advance(result)
      }
      return readSnapshotStage(project, checkCurrent, trace) {
        transaction.snapshot(
          inputs,
          moduleFingerprints,
          shortNames,
          sourceModulesMayHaveChanged = pending.sourceModulesMayHaveChanged,
        )
      }
    } finally {
      scan.traceSummary(trace)
      work?.report()
    }
  }

  /** One item spans read-action retries, while its read time includes every admitted attempt. */
  private suspend fun readShardStage(
    virtualFile: VirtualFile,
    pending: SourceSnapshotChanges,
    shortNames: Set<String>,
    checkAnnotations: Boolean,
    trace: IdeTraceOperation?,
    work: IdeTraceWorkSummary?,
    checkCurrent: () -> Unit,
  ): SourceFileShardCache.ReadResult? = work.measure { item ->
    item?.file = ideTraceFilePath(project, virtualFile)
    val result =
      readSnapshotStage(project, checkCurrent, trace) {
        item.measureRead {
          if (item != null) {
            item.module =
              ProjectFileIndex.getInstance(project).getModuleForFile(virtualFile)?.name
                ?: "<unknown>"
          }
          readShard(virtualFile, pending, shortNames, checkAnnotations, item)
        }
      }
    item?.cache =
      when {
        result == null -> "skipped"
        result.rebuilt -> "rebuilt"
        else -> "reused"
      }
    result
  }

  private fun readShard(
    virtualFile: VirtualFile,
    pending: SourceSnapshotChanges,
    shortNames: Set<String>,
    checkAnnotations: Boolean,
    trace: IdeTraceWorkItem?,
  ): SourceFileShardCache.ReadResult? {
    if (!virtualFile.isValid) return null
    val file =
      trace.stage("source.file.psi") {
        PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
      } ?: return null
    if (!file.isValid) return null
    if (checkAnnotations && !containsRelevantAnnotation(file, shortNames)) return null
    val revision = if (pending.forcesRebuild(virtualFile)) pending.invalidationRevision else null
    val result =
      trace.stage("source.file.cacheLookup") {
        fileShards.read(file, revision, trace)
      }
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

  /** Includes completed work from a pass that was canceled before producing its snapshot. */
  fun traceSummary(trace: IdeTraceOperation?) {
    trace?.attribute("files.total", total)
    trace?.attribute("files.visited", completed)
    trace?.attribute("files.reused", reused)
    trace?.attribute("files.rebuilt", rebuilt)
    trace?.attribute("files.skipped", completed - reused - rebuilt)
  }
}
