// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import androidx.tracing.wire.TraceDriver
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.IdempotenceChecker
import dev.zacsweers.metro.idea.index.FileShard
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.snapshot.IndexInputs
import dev.zacsweers.metro.idea.index.snapshot.IndexOptionsFingerprint
import dev.zacsweers.metro.idea.index.snapshot.PreparedResolutionSnapshot
import dev.zacsweers.metro.idea.index.snapshot.ResolutionInputCapture
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotBuilder
import dev.zacsweers.metro.idea.index.snapshot.ResolutionSnapshotTarget
import dev.zacsweers.metro.idea.index.snapshot.SnapshotKey
import dev.zacsweers.metro.idea.index.snapshot.SourceFileShardCache
import dev.zacsweers.metro.idea.index.snapshot.SourceSnapshotChanges
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.tracing.IdeTraceOperation
import dev.zacsweers.metro.idea.tracing.IdeTraceOutput
import dev.zacsweers.metro.idea.tracing.IdeTraceRecorder
import dev.zacsweers.metro.idea.tracing.IdeTraceState
import dev.zacsweers.metro.idea.tracing.IdeTraceWorkItem
import dev.zacsweers.metro.idea.tracing.RecordingIdeTraceSink
import dev.zacsweers.metro.idea.tracing.ideTraceFilePath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  fun testForcedShardIsReusedAfterCancellation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val reads = mutableListOf<FileShard>()
    var cancel = true
    val builder = builder { _, shard ->
      reads += shard
      if (cancel) {
        cancel = false
        throw CancellationException("Stop after the first completed shard")
      }
    }
    try {
      prepare(builder, file)
      fail("Expected cancellation after reading a shard")
    } catch (_: CancellationException) {
      // The retry keeps the completed shard even though its candidate was never published.
    }
    prepare(builder, file)
    assertEquals(2, reads.size)
    assertSame(reads[0], reads[1])
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
    val activeClassRead = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val interrupted = AtomicBoolean()
    val pauseClassRead = AtomicBoolean(true)
    val preparation =
      startPreparation(
        builder,
        file,
        publish = { progress ->
          events += progress
          if (
            progress.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS &&
              pauseClassRead.compareAndSet(true, false)
          ) {
            activeClassRead.complete(Unit)
            awaitReadCancellation(release, interrupted)
          }
          if (progress.phase == IndexBuildPhase.BUILDING_GRAPH_INDEX) {
            throw CancellationException("Stop after completing source class resolution")
          }
        },
      )
    try {
      PlatformTestUtil.waitForFuture(activeClassRead, 30_000)
      runInEdtAndWait { runWriteAction {} }
      val stopped = PlatformTestUtil.waitForFuture(preparation, 30_000)
      assertTrue(interrupted.get())
      assertTrue(stopped.exceptionOrNull() is CancellationException)
      assertTrue(events.count { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS } >= 2)
      // The next preparation must reuse the completed pass, including after earlier read retries.
      events.clear()
      val prepared = prepare(builder, file) { events += it }
      assertNotNull(prepared.source?.librarySummary)
      assertFalse(events.any { it.phase == IndexBuildPhase.RESOLVING_CLASS_BINDINGS })
    } finally {
      release.countDown()
      PlatformTestUtil.waitForFuture(preparation, 30_000)
    }
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

  private fun builder(
    onCapture: (Set<VirtualFile>) -> Unit = {},
    onShardRead: (KtFile, FileShard) -> Unit = { _, _ -> },
  ): ResolutionSnapshotBuilder {
    val inputCapture = ResolutionInputCapture(project) { _, _ -> }
    return ResolutionSnapshotBuilder(project, onShardRead) { indexBuilder, declarationFiles ->
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
            progress = IndexBuildProgressReporter(publish),
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
