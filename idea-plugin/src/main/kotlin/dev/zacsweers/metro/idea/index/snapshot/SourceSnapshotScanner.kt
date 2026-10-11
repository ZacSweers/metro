// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import dev.zacsweers.metro.compiler.parallelMap
import dev.zacsweers.metro.idea.SOURCE_SCAN_POOL_SIZE_RANGE
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildFile
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkSummary
import dev.zacsweers.metro.idea.tracing.ideTraceFilePath
import dev.zacsweers.metro.idea.tracing.measure
import dev.zacsweers.metro.idea.tracing.measureRead
import dev.zacsweers.metro.idea.tracing.stage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
  private val captureFingerprints: (KtFile, FileShard) -> Map<VirtualFile, String> = { _, _ ->
    emptyMap()
  },
  private val acceptFingerprints: (Map<VirtualFile, String>) -> Unit = {},
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
    parallelism: Int = 1,
    checkCurrent: () -> Unit,
  ): SourceSnapshot = coroutineScope {
    // The refresh captures its pool size once. Each file has one owner in this pass.
    require(parallelism in SOURCE_SCAN_POOL_SIZE_RANGE)
    val orderedFiles = files.distinct()
    val transaction = SourceSnapshotTransaction(previous)
    val scan = SourceScanProgress(progress, orderedFiles.size + pending.requested.size, parallelism)
    val work = trace?.let { IdeTraceWorkSummary(it, "source.file") }
    val fingerprints = mutableMapOf<VirtualFile, String>()
    // Flush file changes even when a single remaining read spends a long time waiting for the IDE.
    val progressUpdates = launch {
      while (isActive) {
        delay(250)
        scan.refresh()
      }
    }

    fun accept(file: VirtualFile, result: FileReadResult?, removeMissing: Boolean) {
      checkCurrent()
      if (result == null) {
        if (removeMissing) {
          transaction.removeShard(file)
        }
        return
      }
      for ((dependency, fingerprint) in result.fingerprints) {
        val earlier = fingerprints.putIfAbsent(dependency, fingerprint)
        if (earlier != null && earlier != fingerprint) {
          throw SourceSnapshotConflictException()
        }
      }
      acceptFingerprints(result.fingerprints)
      transaction.applyShard(file, result.cached.shard)
    }

    suspend fun read(file: VirtualFile, checkAnnotations: Boolean): FileReadResult? {
      val worker = scan.started(IndexBuildFile(file.name, file.presentableUrl))
      var result: FileReadResult? = null
      var completed = false
      try {
        result =
          readShardStage(file, pending, shortNames, checkAnnotations, trace, work, checkCurrent) {
            activity ->
            scan.reading(worker, activity)
          }
        completed = true
        return result
      } finally {
        scan.finished(worker, result?.cached, completed)
      }
    }

    try {
      orderedFiles.parallelMap(
        parallelism,
        read = { read(it, previous != null) },
        accept = { file, result -> accept(file, result, removeMissing = true) },
      )
      // Stub loading can surface requested files before their annotations reach the stub index.
      // Draining them here keeps them from lingering until another cold sweep.
      for (virtualFile in pending.requested) {
        if (transaction.containsShard(virtualFile)) {
          scan.advance(null)
          continue
        }
        val result = read(virtualFile, checkAnnotations = true)
        accept(virtualFile, result, removeMissing = false)
      }
      readSnapshotStage(project, checkCurrent, trace) {
        transaction.snapshot(
          inputs,
          moduleFingerprints,
          shortNames,
          sourceModulesMayHaveChanged = pending.sourceModulesMayHaveChanged,
        )
      }
    } finally {
      progressUpdates.cancel()
      // The pool has joined. Clear file rows promptly when cancellation leaves counts unfinished.
      scan.refresh(force = true)
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
    onRead: (IndexBuildFile) -> Unit,
  ): FileReadResult? = work.measure { item ->
    item?.file = ideTraceFilePath(project, virtualFile)
    val result =
      readSnapshotStage(project, checkCurrent, trace) {
        item.measureRead {
          if (virtualFile.isValid) {
            val fileIndex = ProjectFileIndex.getInstance(project)
            val module = fileIndex.getModuleForFile(virtualFile)?.name
            val root = fileIndex.getContentRootForFile(virtualFile)
            val path = root?.let { VfsUtilCore.getRelativePath(virtualFile, it, '/') }
            onRead(IndexBuildFile(virtualFile.name, path ?: virtualFile.presentableUrl, module))
            item?.module = module ?: "<unknown>"
          }
          readShard(virtualFile, pending, shortNames, checkAnnotations, item)
        }
      }
    item?.cache =
      when {
        result == null -> "skipped"
        result.cached.rebuilt -> "rebuilt"
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
  ): FileReadResult? {
    if (!virtualFile.isValid) {
      return null
    }
    val file =
      trace.stage("source.file.psi") {
        PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
      } ?: return null
    if (!file.isValid) {
      return null
    }
    if (checkAnnotations && !containsRelevantAnnotation(file, shortNames)) {
      return null
    }
    val revision =
      if (pending.forcesRebuild(virtualFile)) {
        pending.invalidationRevision
      } else {
        null
      }
    val result =
      trace.stage("source.file.cacheLookup") {
        fileShards.read(file, revision, trace)
      }
    onShardRead(file, result.shard)
    val fingerprints =
      trace.stage("source.file.fingerprints") {
        captureFingerprints(file, result.shard)
      }
    return FileReadResult(result, fingerprints)
  }

  /** Only detached source data crosses from a worker read to ordered snapshot application. */
  private data class FileReadResult(
    val cached: SourceFileShardCache.ReadResult,
    val fingerprints: Map<VirtualFile, String>,
  )
}

/** Different reads observed different contents of a shared dependency in the same attempt. */
internal class SourceSnapshotConflictException : RuntimeException()

/** Counts successful reads separately from visited files, including skipped or removed files. */
private class SourceScanProgress(
  private val reporter: IndexBuildProgressReporter,
  private val total: Int,
  private val workerLimit: Int,
) {
  private var completed = 0
  private var reused = 0
  private var rebuilt = 0
  private var activeWorkers = 0
  private var peakWorkers = 0
  private val workerFiles = arrayOfNulls<IndexBuildFile>(workerLimit)

  init {
    report()
  }

  @Synchronized
  fun advance(result: SourceFileShardCache.ReadResult?) {
    if (result != null) {
      if (result.rebuilt) {
        rebuilt++
      } else {
        reused++
      }
    }
    completed++
    report()
  }

  /** A read owns its display slot across suspension and read-action retries. */
  @Synchronized
  fun started(file: IndexBuildFile): Int {
    val worker = workerFiles.indexOfFirst { it == null }
    check(worker >= 0) { "Source reads exceeded the configured pool size" }
    workerFiles[worker] = file
    activeWorkers++
    peakWorkers = maxOf(peakWorkers, activeWorkers)
    report()
    return worker
  }

  /** Module and content-root information becomes available when IDE read access is admitted. */
  @Synchronized
  fun reading(worker: Int, file: IndexBuildFile) {
    workerFiles[worker] = file
    report()
  }

  @Synchronized
  fun finished(worker: Int, result: SourceFileShardCache.ReadResult?, completed: Boolean) {
    workerFiles[worker] = null
    activeWorkers--
    if (completed) {
      advance(result)
    } else {
      report()
    }
  }

  private fun report(force: Boolean = false) {
    reporter.counted(
      IndexBuildPhase.ANALYZING_DECLARATIONS,
      completed,
      total,
      reused,
      rebuilt,
      activeWorkers = activeWorkers,
      workerLimit = workerLimit,
      workerFiles = workerFiles.toList(),
      force = force,
    )
  }

  /** Flushes the latest counters after the reporter's throttle interval passes. */
  @Synchronized fun refresh(force: Boolean = false) = report(force)

  /** Includes completed work from a pass that was canceled before producing its snapshot. */
  fun traceSummary(trace: IdeTraceOperation?) {
    trace?.attribute("files.total", total)
    trace?.attribute("files.visited", completed)
    trace?.attribute("files.reused", reused)
    trace?.attribute("files.rebuilt", rebuilt)
    trace?.attribute("files.skipped", completed - reused - rebuilt)
    trace?.attribute("files.workers", workerLimit)
    trace?.attribute("files.peakWorkers", peakWorkers)
  }
}
