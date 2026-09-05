// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import androidx.tracing.wire.TraceDriver
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.CachedValueBase
import com.intellij.util.IdempotenceChecker
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.snapshot.IndexInputs
import dev.zacsweers.metro.idea.index.snapshot.IndexOptionsFingerprint
import dev.zacsweers.metro.idea.index.snapshot.PreparedResolutionSnapshot
import dev.zacsweers.metro.idea.index.snapshot.ResolutionInputCapture
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotBuilder
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotTarget
import dev.zacsweers.metro.idea.index.snapshot.SnapshotKey
import dev.zacsweers.metro.idea.index.snapshot.SourceFileShardCache
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshotChanges
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshotConflictException
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceOutput
import dev.zacsweers.metro.idea.tracing.IdeTraceRecorder
import dev.zacsweers.metro.idea.tracing.IdeTraceState
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.RecordingIdeTraceSink
import dev.zacsweers.metro.idea.tracing.ideTraceFilePath
import java.lang.ref.Reference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile

/** Exercises retained snapshot stages and cancellation by real IDE write actions. */
class MetroSnapshotRetryTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    // Light fixtures retain their project service. Stop its automatic scans before this test's
    // separate builder starts forcing shard revisions in the shared PSI cache.
    MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
    val service = project.service<MetroResolutionService>()
    service.settingsChanged()
    val drained = CompletableFuture.runAsync {
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    }
    PlatformTestUtil.waitForFuture(drained, 30_000)
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  fun testForcedShardIsReusedAfterCancellation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    val reads = mutableListOf<FileShard>()
    var retainedCacheEntry: CachedValueBase.Data<*>? = null
    var cancel = true
    val builder = builder { readFile, shard ->
      reads += shard
      if (cancel) {
        retainedCacheEntry = cachedShardEntry(readFile, shard)
        cancel = false
        throw CancellationException("Stop after the first completed shard")
      }
    }
    try {
      try {
        prepare(builder, file)
        fail("Expected cancellation after reading a shard")
      } catch (_: CancellationException) {
        // The retry keeps the completed shard even though its candidate was never published.
      }
      prepare(builder, file)
      assertEquals(2, reads.size)
      assertSame(reads[0], reads[1])
    } finally {
      // IntelliJ holds cache entries through soft references. Holding the shard alone permits
      // eviction.
      Reference.reachabilityFence(retainedCacheEntry)
    }
  }

  fun testNewForcedRevisionRebuildsTheShard() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val reads = mutableListOf<FileShard>()
    val builder = builder { _, shard -> reads += shard }
    prepare(builder, file, revision = 1)
    prepare(builder, file, revision = 2)
    val events = mutableListOf<IndexBuildProgress>()
    prepare(builder, file, revision = 2) { events += it }
    assertEquals(3, reads.size)
    assertNotSame(reads[0], reads[1])
    assertSame(reads[1], reads[2])
    val completed = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
    assertEquals(1, completed.reused)
    assertEquals(0, completed.rebuilt)
  }

  fun testUnannotatedShardIsReused() {
    val file = myFixture.configureMetroFile("class Unrelated")
    val cache = SourceFileShardCache()
    allowAnalysisOnEdt {
      val first = cache.read(file, null)
      assertTrue(first.shard.bindings.isEmpty())
      assertTrue(first.rebuilt)
      assertFalse(cache.read(file, null).rebuilt)
    }
  }

  fun testShardCacheUsesOnlyTheCurrentTraceItem() {
    val file =
      myFixture.configureMetroFile(
        """
      typealias GraphAlias = dev.zacsweers.metro.DependencyGraph
      @GraphAlias interface AppGraph
      """
      )
    // Random cache-consistency checks intentionally recompute values during ordinary cache hits.
    IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
    val cache = SourceFileShardCache()
    val firstTicks = AtomicLong()
    val firstItem = IdeTraceWorkItem(firstTicks::incrementAndGet)
    allowAnalysisOnEdt {
      val first = cache.read(file, 1, firstItem)
      assertTrue(first.rebuilt)
      assertTrue(firstItem.stageTotals.isNotEmpty())
      val aliasLookup = firstItem.stageTotals["source.file.typealiasLookup"]
      assertNotNull(aliasLookup)
      assertTrue(checkNotNull(aliasLookup).attempts > 0)
      val completedFirstTicks = firstTicks.get()
      val completedFirstStages = firstItem.stageTotals.values.sumOf { it.attempts }

      val untraced = cache.read(file, 2)
      assertTrue(untraced.rebuilt)
      assertNotSame(first.shard, untraced.shard)
      assertEquals(completedFirstTicks, firstTicks.get())
      assertEquals(completedFirstStages, firstItem.stageTotals.values.sumOf { it.attempts })

      val secondTicks = AtomicLong()
      val secondItem = IdeTraceWorkItem(secondTicks::incrementAndGet)
      val retraced = cache.read(file, 3, secondItem)
      assertTrue(retraced.rebuilt)
      assertNotSame(untraced.shard, retraced.shard)
      assertTrue(secondItem.stageTotals.isNotEmpty())
      assertEquals(completedFirstTicks, firstTicks.get())
      val completedSecondTicks = secondTicks.get()
      val completedSecondStages = secondItem.stageTotals.values.sumOf { it.attempts }

      val reused = cache.read(file, 3, secondItem)
      assertFalse(reused.rebuilt)
      assertSame(retraced.shard, reused.shard)
      assertEquals(completedSecondTicks, secondTicks.get())
      assertEquals(completedSecondStages, secondItem.stageTotals.values.sumOf { it.attempts })
      assertEquals(completedFirstTicks, firstTicks.get())
    }
  }

  fun testWriteActionRetriesOnlyTheActiveFileRead() = withSnapshotTrace { recorder, sink ->
    val file = configureTwoFiles()
    val reads = mutableListOf<Pair<VirtualFile, FileShard>>()
    val readFiles = linkedSetOf<VirtualFile>()
    val activeRead = CompletableFuture<List<Pair<VirtualFile, FileShard>>>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val pauseRead = AtomicBoolean(true)
    val events = mutableListOf<IndexBuildProgress>()
    val builder = builder { readFile, shard ->
      reads += readFile.virtualFile to shard
      readFiles += readFile.virtualFile
      if (readFiles.size == 2 && pauseRead.compareAndSet(true, false)) {
        activeRead.complete(reads.toList())
        awaitReadCancellation(release, interrupted)
      }
    }
    val preparation = startPreparation(builder, file, recorder = recorder, publish = events::add)
    try {
      val readsBeforeWrite = PlatformTestUtil.waitForFuture(activeRead, 30_000)
      val completedFile = readsBeforeWrite.first().first
      val completedReads = readsBeforeWrite.filter { it.first == completedFile }
      val completedShard = completedReads.last().second
      runInEdtAndWait { runWriteAction {} }
      val prepared = awaitPreparation(preparation)
      assertTrue("The write action must interrupt an active read", interrupted.get())
      assertEquals(1, events.count { it.phase == IndexBuildPhase.DISCOVERING_SOURCE_FILES })
      assertEquals(completedReads.size, reads.count { it.first == completedFile })
      assertSame(completedShard, prepared.source!!.shards[completedFile])
      val finalProgress = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
      assertEquals(finalProgress.total, finalProgress.completed)
      assertEquals(2, finalProgress.reused!! + finalProgress.rebuilt!!)
      recorder.stop()
      runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } } }
      val scan = sink.results("source.scan").single().metadata
      assertEquals("completed", scan["outcome"])
      val rebuilt = checkNotNull(scan["files.rebuilt"]).toInt()
      val reused = checkNotNull(scan["files.reused"]).toInt()
      assertEquals(2, rebuilt + reused)
      assertTrue(checkNotNull(scan["read_attempts"]).toInt() > 2)
      assertTrue(checkNotNull(scan["canceled_read_attempts"]).toInt() >= 1)
      assertEquals(1, sink.results("source.discover").size)
      assertEquals(1, sink.results("snapshot.prepare").size)
      val fileWork = sink.results("source.file.item").map { it.metadata }
      assertEquals(2, fileWork.size)
      val retriedPath = ideTraceFilePath(project, readsBeforeWrite.last().first)
      val retriedWork = fileWork.single { it["file"] == retriedPath }
      assertEquals("reused", retriedWork["cache"])
      assertTrue(checkNotNull(retriedWork["read_attempts"]).toInt() >= 2)
      assertTrue(checkNotNull(retriedWork["canceled_read_attempts"]).toInt() >= 1)
      assertTrue(checkNotNull(retriedWork["canceled_read_elapsed_ns"]).toLong() > 0)
      assertEquals(module.name, retriedWork["module"])
      assertTrue(checkNotNull(retriedWork["stage.source.file.cacheLookup.attempts"]).toInt() >= 2)
      for (stage in
        listOf(
          "source.file.psi",
          "source.file.imports",
          "source.file.annotationScan",
          "source.file.annotationLookup",
          "source.file.declarationExtraction",
          "source.file.dynamicGraphScan",
          "source.file.shardConstruction",
        )) {
        assertTrue(
          "Expected aggregate time for $stage",
          checkNotNull(retriedWork["stage.$stage.elapsed_ns"]).toLong() >= 0,
        )
        assertTrue(
          "Expected a retained interval for $stage",
          sink.results(stage).any { it.metadata["file"] == retriedPath },
        )
      }
      for (phase in
        listOf(
          "source.buildOwnershipIndex",
          "source.consumerOwnership",
          "source.resolveClassRequests",
          "source.collectLibraryInputs",
        )) {
        assertEquals("Expected one completed $phase", 1, sink.results(phase).size)
      }
      val classWork = sink.results("source.class.item").map { it.metadata }
      val exampleWork = classWork.single { it["class"] == "test.Example" }
      assertEquals("resolved", exampleWork["outcome"])
      assertEquals("reused", exampleWork["cache"])
      for (stage in
        listOf(
          "source.class.analysisEntry",
          "source.class.analysisSetup",
          "source.class.findClass",
          "source.class.declarationEligibility",
          "source.class.optionsAndQualifierLookup",
          "source.class.cacheCheck",
          "source.class.analysisExit",
          "source.class.dependencyExpansion",
        )) {
        assertTrue(
          "Expected aggregate time for $stage",
          checkNotNull(exampleWork["stage.$stage.elapsed_ns"]).toLong() >= 0,
        )
        assertTrue(
          "Expected a retained interval for $stage",
          sink.results(stage).any { it.metadata["class"] == "test.Example" },
        )
      }
      assertNull(exampleWork["stage.source.class.bindingConstruction.attempts"])
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testWriteActionRetriesBothParallelReadsAndKeepsCompletedCacheEntries() =
    withSnapshotTrace { recorder, sink ->
      IdempotenceChecker.disableRandomChecksUntil(testRootDisposable)
      val file = configureTwoFiles()
      val gate = ParallelReadGate()
      val events = ConcurrentLinkedQueue<IndexBuildProgress>()
      val workerSlots = ConcurrentLinkedQueue<Pair<VirtualFile, Int>>()
      val builder =
        builder(poolSize = 2) { readFile, shard ->
          val current = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
          val slot = current.workerFiles.indexOfFirst { it?.name == readFile.name }
          assertTrue("Expected an occupied slot for ${readFile.name}", slot >= 0)
          workerSlots += readFile.virtualFile to slot
          gate.onRead(readFile, shard)
        }
      val preparation =
        startPreparation(
          builder,
          file,
          recorder = recorder,
          progressIntervalNanos = 0,
          publish = events::add,
        )
      try {
        PlatformTestUtil.waitForFuture(gate.active, 30_000)
        assertEquals(2, gate.firstReads.size)
        val occupied = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
        val occupiedFiles = occupied.workerFiles.filterNotNull()
        assertEquals(
          gate.firstReads.keys.map { it.name }.toSet(),
          occupiedFiles.map { it.name }.toSet(),
        )
        assertTrue(occupiedFiles.all { it.module == module.name })
        assertEquals("test/Example.kt", occupiedFiles.single { it.name == "Example.kt" }.path)
        runInEdtAndWait { runWriteAction {} }
        val prepared = awaitPreparation(preparation)
        assertEquals(gate.firstReads.keys, gate.interrupted)
        for ((readFile, cached) in gate.firstReads) {
          assertSame(cached, prepared.source!!.shards[readFile])
          val attempts = gate.reads.filter { it.first == readFile }
          assertTrue("Expected a retry for ${readFile.name}", attempts.size >= 2)
          assertTrue(attempts.all { it.second === cached })
          val slots = workerSlots.filter { it.first == readFile }.map { it.second }
          assertEquals("A read keeps its worker row across write retries", 1, slots.distinct().size)
        }
        val progress = events.filter { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
        assertTrue(progress.any { it.activeWorkers == 2 })
        assertTrue(progress.all { it.workerLimit == 2 })
        val completed = progress.last()
        assertEquals(completed.total, completed.completed)
        assertEquals(0, completed.activeWorkers)
        assertEquals(listOf(null, null), completed.workerFiles)
        assertEquals(
          "Published snapshots survive later worker updates",
          occupiedFiles,
          occupied.workerFiles.filterNotNull(),
        )
        assertEquals(2, completed.reused)
        assertEquals(0, completed.rebuilt)
        assertEquals(1, events.count { it.phase == IndexBuildPhase.DISCOVERING_SOURCE_FILES })

        recorder.stop()
        runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } } }
        val scan = sink.results("source.scan").single().metadata
        assertEquals("completed", scan["outcome"])
        assertEquals("2", scan["files.workers"])
        assertEquals("2", scan["files.peakWorkers"])
        assertEquals("2", scan["source.file.items"])
        assertTrue(checkNotNull(scan["canceled_read_attempts"]).toInt() >= 2)
        val items = sink.results("source.file.item").map { it.metadata }
        assertEquals(2, items.size)
        for (item in items) {
          assertEquals("completed", item["outcome"])
          assertEquals("reused", item["cache"])
          assertTrue(checkNotNull(item["read_attempts"]).toInt() >= 2)
          assertTrue(checkNotNull(item["canceled_read_attempts"]).toInt() >= 1)
        }
      } finally {
        gate.release.countDown()
        PlatformTestUtil.waitForFuture(preparation, 30_000)
      }
    }

  fun testParallelReadsRejectConflictingDependencyFingerprintsBeforeAggregation() {
    val file = configureTwoFiles()
    val dependency = file.virtualFile
    val events = mutableListOf<IndexBuildProgress>()
    val builder =
      builder(
        poolSize = 2,
        captureFingerprints = { readFile, _ -> mapOf(dependency to readFile.name) },
      )
    val preparation = startPreparation(builder, file, publish = events::add)
    val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
    assertTrue(result.exceptionOrNull() is SourceSnapshotConflictException)
    assertFalse(events.any { it.phase == IndexBuildPhase.COMBINING_DECLARATIONS })
  }

  fun testProgressPublishesPartiallyOccupiedPoolWhileReadsWait() {
    val file = configureTwoFiles()
    val gate = ParallelReadGate()
    val occupied = CompletableFuture<IndexBuildProgress>()
    val builder = builder(poolSize = 4, onShardRead = gate::onRead)
    val preparation =
      startPreparation(
        builder,
        file,
        publish = { progress ->
          if (progress.activeWorkers == 2 && progress.workerLimit == 4) {
            occupied.complete(progress)
          }
        },
      )
    try {
      PlatformTestUtil.waitForFuture(gate.active, 30_000)
      val progress = PlatformTestUtil.waitForFuture(occupied, 30_000)
      assertEquals(0, progress.completed)
      assertEquals(4, progress.workerFiles.size)
      assertEquals(
        gate.firstReads.keys.map { it.name }.toSet(),
        progress.workerFiles.filterNotNull().map { it.name }.toSet(),
      )
    } finally {
      gate.release.countDown()
      awaitPreparation(preparation)
    }
  }

  fun testProgressShowsTheCurrentFileWithOneWorker() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val entered = CompletableFuture<Unit>()
    val occupied = CompletableFuture<IndexBuildProgress>()
    val release = CountDownLatch(1)
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val builder = builder { _, _ ->
      entered.complete(Unit)
      awaitReadCancellation(release, AtomicBoolean())
    }
    val preparation =
      startPreparation(
        builder,
        file,
        publish = { progress ->
          events += progress
          val current = progress.workerFiles.singleOrNull()
          if (current?.name == file.name && current.module == module.name) {
            occupied.complete(progress)
          }
        },
      )
    try {
      PlatformTestUtil.waitForFuture(entered, 30_000)
      val progress = PlatformTestUtil.waitForFuture(occupied, 30_000)
      assertEquals(0, progress.completed)
      assertEquals(1, progress.workerLimit)
      assertEquals(1, progress.activeWorkers)
      val current = checkNotNull(progress.workerFiles.single())
      assertEquals(file.name, current.name)
      assertTrue(current.path.endsWith(file.name))
      assertEquals(module.name, current.module)
      release.countDown()
      awaitPreparation(preparation)
      val completed = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
      assertEquals(listOf(null), completed.workerFiles)
      assertEquals(current, progress.workerFiles.single())
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testParallelPreparationPreservesSerialOrderWhenTheSecondFileFinishesFirst() {
    val file = configureTwoFiles()
    val serial = prepare(builder(), file)
    val serialSource = checkNotNull(serial.source)
    val firstFile = serialSource.shardOrder.first()
    val entered = CompletableFuture<Unit>()
    val laterCompleted = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val builder =
      builder(poolSize = 2) { readFile, _ ->
        if (readFile.virtualFile == firstFile) {
          entered.complete(Unit)
          awaitReadCancellation(release, interrupted)
        }
      }
    val preparation =
      startPreparation(
        builder,
        file,
        progressIntervalNanos = 0,
        publish = { progress ->
          if (progress.phase == IndexBuildPhase.ANALYZING_DECLARATIONS && progress.completed == 1) {
            laterCompleted.complete(Unit)
          }
        },
      )
    try {
      PlatformTestUtil.waitForFuture(entered, 30_000)
      PlatformTestUtil.waitForFuture(laterCompleted, 30_000)
      release.countDown()
      val parallel = awaitPreparation(preparation)
      val parallelSource = checkNotNull(parallel.source)
      assertEquals(serialSource.shardOrder, parallelSource.shardOrder)
      for (readFile in serialSource.shardOrder) {
        val serialShard = checkNotNull(serialSource.shards[readFile])
        val parallelShard = checkNotNull(parallelSource.shards[readFile])
        assertEquals(
          serialShard.bindings.map { it.typeKey },
          parallelShard.bindings.map { it.typeKey },
        )
        assertEquals(serialShard.consumers.map { it.key }, parallelShard.consumers.map { it.key })
        assertEquals(serialShard.graphs.map { it.name }, parallelShard.graphs.map { it.name })
        assertEquals(serialShard.dependencyFiles, parallelShard.dependencyFiles)
        assertEquals(serialShard.sharedDeclarationFiles, parallelShard.sharedDeclarationFiles)
        assertEquals(
          serialSource.dependencyOwnersFor(readFile),
          parallelSource.dependencyOwnersFor(readFile),
        )
        assertEquals(
          serialSource.sharedDeclarationOwners[readFile],
          parallelSource.sharedDeclarationOwners[readFile],
        )
      }
      val serialIndex = serial.buildIndexes {}.values.single()
      val parallelIndex = parallel.buildIndexes {}.values.single()
      assertEquals(serialIndex.graphs.map { it.name }, parallelIndex.graphs.map { it.name })
      val serialAccessor = serialIndex.accessorsFor(serialIndex.graphs.single()).single()
      val parallelAccessor = parallelIndex.accessorsFor(parallelIndex.graphs.single()).single()
      assertEquals(serialAccessor.key, parallelAccessor.key)
      val serialBinding =
        serialIndex.resolveConsumer(serialAccessor).uniformBindings.orEmpty().single()
      val parallelBinding =
        parallelIndex.resolveConsumer(parallelAccessor).uniformBindings.orEmpty().single()
      assertEquals(serialBinding.typeKey, parallelBinding.typeKey)
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testParentCancellationJoinsBothParallelReadsBeforePreparationReturns() =
    withSnapshotTrace { recorder, sink ->
      val file = configureTwoFiles()
      val gate = ParallelReadGate()
      val parent = Job()
      val events = ConcurrentLinkedQueue<IndexBuildProgress>()
      val builder = builder(poolSize = 2, onShardRead = gate::onRead)
      val preparation =
        startPreparation(
          builder,
          file,
          parentJob = parent,
          recorder = recorder,
          publish = events::add,
        )
      try {
        PlatformTestUtil.waitForFuture(gate.active, 30_000)
        val occupied = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
        val occupiedFiles = occupied.workerFiles.filterNotNull()
        assertEquals(2, occupiedFiles.size)
        parent.cancel()
        val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(gate.firstReads.keys, gate.interrupted)
        assertFalse(events.any { it.phase == IndexBuildPhase.COMBINING_DECLARATIONS })
        val canceled = events.last { it.phase == IndexBuildPhase.ANALYZING_DECLARATIONS }
        assertEquals(0, canceled.activeWorkers)
        assertEquals(listOf(null, null), canceled.workerFiles)
        assertEquals(occupiedFiles, occupied.workerFiles.filterNotNull())
        recorder.stop()
        runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } } }
        val scan = sink.results("source.scan").single().metadata
        assertEquals("canceled", scan["outcome"])
        assertEquals("2", scan["files.peakWorkers"])
        assertEquals("2", scan["source.file.items"])
        val items = sink.results("source.file.item").map { it.metadata }
        assertEquals(2, items.size)
        assertTrue(items.all { it["outcome"] == "canceled" })
      } finally {
        parent.cancel()
        gate.release.countDown()
        PlatformTestUtil.waitForFuture(preparation, 30_000)
      }
    }

  /** Both first reads retain their completed cache entries while waiting for real cancellation. */
  private inner class ParallelReadGate {
    val active = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val reads = ConcurrentLinkedQueue<Pair<VirtualFile, FileShard>>()
    val firstReads = ConcurrentHashMap<VirtualFile, FileShard>()
    val interrupted = ConcurrentHashMap.newKeySet<VirtualFile>()
    private val entered = AtomicInteger()

    fun onRead(file: KtFile, shard: FileShard) {
      val virtualFile = file.virtualFile
      reads += virtualFile to shard
      if (firstReads.putIfAbsent(virtualFile, shard) != null) {
        return
      }
      if (entered.incrementAndGet() == 2) {
        active.complete(Unit)
      }
      val canceled = AtomicBoolean()
      try {
        awaitReadCancellation(release, canceled)
      } finally {
        if (canceled.get()) {
          interrupted += virtualFile
        }
      }
    }
  }

  /** Runs enabled tracing with an in-memory sink while the fixture exercises real read retries. */
  private fun withSnapshotTrace(block: (IdeTraceRecorder, RecordingIdeTraceSink) -> Unit) {
    val job = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(job + Dispatchers.Default),
        createOutput = { IdeTraceOutput(TraceDriver(sink)) },
      )
    try {
      recorder.start()
      runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.RECORDING } } }
      block(recorder, sink)
    } finally {
      recorder.stop()
      runBlocking {
        withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } }
        job.cancelAndJoin()
      }
    }
  }

  fun testSourceChangeRejectsCompletedFileCheckpoints() {
    val file = configureTwoFiles()
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val activeRead = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val revision = AtomicLong()
    val readFiles = linkedSetOf<VirtualFile>()
    val pauseRead = AtomicBoolean(true)
    val builder = builder { readFile, _ ->
      readFiles += readFile.virtualFile
      if (readFiles.size == 2 && pauseRead.compareAndSet(true, false)) {
        activeRead.complete(Unit)
        awaitReadCancellation(release, interrupted)
      }
    }
    val preparation =
      startPreparation(
        builder,
        file,
        checkCurrent = { if (revision.get() != 0L) throw ChangedSnapshotInputs() },
      )
    try {
      PlatformTestUtil.waitForFuture(activeRead, 30_000)
      WriteCommandAction.runWriteCommandAction(project) {
        document.setText(document.text.replace("val example: Example", "val text: String"))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        revision.incrementAndGet()
      }
      val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
      assertTrue(interrupted.get())
      assertTrue(result.exceptionOrNull() is ChangedSnapshotInputs)
      val fresh = prepare(builder, file, revision = revision.get())
      val accessor = fresh.source!!.shards[file.virtualFile]!!.consumers.single()
      assertEquals("kotlin.String", accessor.key.renderedType)
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testWriteDuringFinalCaptureKeepsCompletedPreparationStages() {
    val file =
      myFixture.configureMetroFile(
        "@Inject class Example; @DependencyGraph interface AppGraph { val example: Example }"
      )
    val activeCapture = CompletableFuture<Pair<Int, List<IndexBuildProgress>>>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val pauseCapture = AtomicBoolean(true)
    val events = mutableListOf<IndexBuildProgress>()
    var sourceReads = 0
    val builder =
      builder(
        onCapture = { declarationFiles ->
          if (declarationFiles.isNotEmpty() && pauseCapture.compareAndSet(true, false)) {
            activeCapture.complete(sourceReads to events.toList())
            awaitReadCancellation(release, interrupted)
          }
        },
        onShardRead = { _, _ -> sourceReads++ },
      )
    val preparation =
      startPreparation(
        builder,
        file,
        resolveFromLibraries = true,
        publish = events::add,
      )
    try {
      val (readsBeforeWrite, eventsBeforeWrite) =
        PlatformTestUtil.waitForFuture(activeCapture, 30_000)
      assertTrue(readsBeforeWrite > 0)
      val completedPhases =
        setOf(
          IndexBuildPhase.DISCOVERING_SOURCE_FILES,
          IndexBuildPhase.ANALYZING_DECLARATIONS,
          IndexBuildPhase.COMBINING_DECLARATIONS,
          IndexBuildPhase.RESOLVING_CLASS_BINDINGS,
          IndexBuildPhase.READING_DEPENDENCY_METADATA,
        )
      for (phase in completedPhases) {
        assertTrue(
          "Expected completed work for $phase",
          eventsBeforeWrite.any { it.phase == phase },
        )
      }
      runInEdtAndWait { runWriteAction {} }
      val prepared = awaitPreparation(preparation)
      assertTrue(interrupted.get())
      assertEquals(readsBeforeWrite, sourceReads)
      assertEquals(
        eventsBeforeWrite.filter { it.phase in completedPhases },
        events.filter { it.phase in completedPhases },
      )
      val index = prepared.buildIndexes {}.values.single()
      val graph = index.graphs.single()
      assertEquals("AppGraph", graph.name)
      val accessor = index.accessorsFor(graph).single()
      val binding = index.resolveConsumer(accessor).uniformBindings.orEmpty().single()
      assertEquals("test.Example", binding.typeKey.renderedType)
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testParentCancellationStopsPreparationWithoutAResult() {
    val file = configureTwoFiles()
    val activeRead = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val parent = Job()
    val events = mutableListOf<IndexBuildProgress>()
    val builder = builder { _, _ ->
      activeRead.complete(Unit)
      awaitReadCancellation(release, interrupted)
    }
    val preparation = startPreparation(builder, file, parentJob = parent, publish = events::add)
    try {
      PlatformTestUtil.waitForFuture(activeRead, 30_000)
      parent.cancel()
      val result = PlatformTestUtil.waitForFuture(preparation, 30_000)
      assertTrue(interrupted.get())
      assertTrue(result.exceptionOrNull() is CancellationException)
      assertFalse(events.any { it.phase == IndexBuildPhase.COMBINING_DECLARATIONS })
    } finally {
      parent.cancel()
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
  }

  fun testCompletedClassResolutionIsReusedAfterCancellation() {
    val file =
      myFixture.configureMetroFile(
        "@Inject class Example; @DependencyGraph interface AppGraph { val example: Example }"
      )
    val builder = builder()
    val events = mutableListOf<IndexBuildProgress>()
    try {
      prepare(builder, file) { progress ->
        events += progress
        if (progress.phase == IndexBuildPhase.BUILDING_GRAPH_INDEX) {
          throw CancellationException("Stop after completing source class resolution")
        }
      }
      fail("Expected cancellation after completing source class resolution")
    } catch (_: CancellationException) {
      // Source resolution is cached before the later graph-index stage begins.
    }
    assertTrue(events.any { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
    events.clear()
    val prepared = prepare(builder, file) { events += it }
    assertNotNull(prepared.source?.librarySummary)
    assertFalse(events.any { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
  }

  fun testCompletedLibraryResolutionIsReusedAfterCancellation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val builder = builder()
    val events = mutableListOf<IndexBuildProgress>()
    try {
      prepare(builder, file, resolveFromLibraries = true) { progress ->
        events += progress
        if (progress.phase == IndexBuildPhase.BUILDING_GRAPH_INDEX) throw ProcessCanceledException()
      }
      fail("Expected cancellation after reading library metadata")
    } catch (_: ProcessCanceledException) {
      // The completed source summary also retains the ownership key for the library cache.
    }
    assertTrue(events.any { it.phase == IndexBuildPhase.READING_DEPENDENCY_METADATA })
    assertTrue(events.any { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
    events.clear()
    prepare(builder, file, resolveFromLibraries = true) { events += it }
    assertFalse(events.any { it.phase == IndexBuildPhase.READING_DEPENDENCY_METADATA })
    assertFalse(events.any { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
  }

  fun testSourceEditInvalidatesCompletedClassResolution() {
    val file =
      myFixture.configureMetroFile(
        "@Inject class Example; @DependencyGraph interface AppGraph { val example: Example }"
      )
    val builder = builder()
    val first = prepare(builder, file)
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    WriteCommandAction.runWriteCommandAction(project) {
      val offset = document.text.indexOf("@Inject class Example")
      document.deleteString(offset, offset + "@Inject ".length)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val second = prepare(builder, file)
    assertNotSame(first.source!!.librarySummary, second.source!!.librarySummary)
    assertTrue(second.source!!.librarySummary!!.sourceClasses.addedBindings.isEmpty())
  }

  fun testClassDependencyEditInvalidatesCompletedClassResolution() {
    val registry =
      myFixture.addFileToProject("test/Registry.kt", "package test; object Registry") as KtFile
    val graph =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val registry: Registry }")
    val builder = builder()
    val first = prepare(builder, graph)
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(registry))
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText("package test; class Registry")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val second = prepare(builder, graph)
    assertNotSame(first.source!!.librarySummary, second.source!!.librarySummary)
    assertTrue(second.source!!.librarySummary!!.sourceClasses.addedBindings.isEmpty())
  }

  /**
   * Finds the completed cache entry under read access so this test can retain it across retries.
   */
  private fun cachedShardEntry(file: KtFile, shard: FileShard): CachedValueBase.Data<*> {
    val userData = (file as UserDataHolderBase).userMap
    for (key in userData.keys) {
      val cachedValue = userData.get(key) as? CachedValueBase<*> ?: continue
      val entry = cachedValue.upToDateOrNull ?: continue
      if (entry.value === shard) return entry
    }
    error("Expected the completed shard to be present in the file cache")
  }

  private fun builder(
    onCapture: (Set<VirtualFile>) -> Unit = {},
    poolSize: Int = 1,
    captureFingerprints: (KtFile, FileShard) -> Map<VirtualFile, String> = { _, _ -> emptyMap() },
    onShardRead: (KtFile, FileShard) -> Unit = { _, _ -> },
  ): ResolutionSnapshotBuilder {
    val inputCapture = ResolutionInputCapture(project) { _, _ -> }
    return ResolutionSnapshotBuilder(
      project,
      onShardRead,
      sourceScanPoolSize = { poolSize },
      captureSourceFingerprints = captureFingerprints,
    ) { indexBuilder, declarationFiles ->
      inputCapture.capture(indexBuilder, declarationFiles)
      onCapture(declarationFiles)
    }
  }

  /** Keeps at least one completed file ahead of the interrupted read. */
  private fun configureTwoFiles(): KtFile {
    myFixture.addFileToProject(
      "test/Example.kt",
      "package test; import dev.zacsweers.metro.Inject; @Inject class Example",
    )
    return myFixture.configureMetroFile(
      "@DependencyGraph interface AppGraph { val example: Example }"
    )
  }

  /**
   * Waits cooperatively so the platform can cancel this read when an EDT write requests the lock.
   */
  private fun awaitReadCancellation(release: CountDownLatch, interrupted: AtomicBoolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    try {
      while (!release.await(1, TimeUnit.MILLISECONDS)) {
        ProgressManager.checkCanceled()
        check(System.nanoTime() < deadline) { "The active read was never canceled" }
      }
    } catch (failure: Throwable) {
      if (failure is ProcessCanceledException || failure is CancellationException) {
        interrupted.set(true)
      }
      throw failure
    }
  }

  /** Each call represents a retry of the same forced source invalidation. */
  private fun prepare(
    builder: ResolutionSnapshotBuilder,
    file: KtFile,
    revision: Long = 0,
    resolveFromLibraries: Boolean = false,
    publish: (IndexBuildProgress) -> Unit = {},
  ): PreparedResolutionSnapshot {
    return awaitPreparation(
      startPreparation(builder, file, revision, resolveFromLibraries, publish = publish)
    )
  }

  /**
   * Pumps EDT events while the worker uses the same suspend preparation entry point as the service.
   */
  private fun awaitPreparation(
    preparation: CompletableFuture<Result<PreparedResolutionSnapshot>>
  ): PreparedResolutionSnapshot = PlatformTestUtil.waitForFuture(preparation, 30_000).getOrThrow()

  private fun startPreparation(
    builder: ResolutionSnapshotBuilder,
    file: KtFile,
    revision: Long = 0,
    resolveFromLibraries: Boolean = false,
    parentJob: Job? = null,
    recorder: IdeTraceRecorder? = null,
    checkCurrent: () -> Unit = {},
    progressIntervalNanos: Long = 250_000_000L,
    publish: (IndexBuildProgress) -> Unit = {},
  ): CompletableFuture<Result<PreparedResolutionSnapshot>> = CompletableFuture.supplyAsync {
    runCatching {
      runBlocking(parentJob ?: EmptyCoroutineContext) {
        val targets =
          smartReadAction(project) {
            listOf(
              ResolutionSnapshotTarget(
                SnapshotKey(
                  IndexOptionsFingerprint(file.metroIdeState().options),
                  resolveFromLibraries,
                ),
                listOf(module),
              )
            )
          }
        suspend fun prepare(trace: IdeTraceOperation?): PreparedResolutionSnapshot =
          builder.prepare(
            previous = null,
            inputs = IndexInputs(0, 0),
            targets = targets,
            pending =
              SourceSnapshotChanges(
                emptySet(),
                setOf(file.virtualFile),
                emptySet(),
                true,
                invalidationRevision = revision,
              ),
            coldSweep = true,
            progress =
              IndexBuildProgressReporter(publish, updateIntervalNanos = progressIntervalNanos),
            generationToken = IndexGenerationToken.create(),
            trace = trace,
            checkCurrent = checkCurrent,
          )
        if (recorder == null) prepare(null)
        else recorder.traceSuspend("snapshot.prepare") { prepare(it) }
      }
    }
  }

  private class ChangedSnapshotInputs : RuntimeException()
}
