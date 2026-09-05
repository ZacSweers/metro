// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.FileShardBuilder
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.SourceClassBindingPostProcessor
import dev.zacsweers.metro.idea.index.SourceClassDependencies
import dev.zacsweers.metro.idea.index.SourceClassResolution
import dev.zacsweers.metro.idea.index.snapshot.ResolutionInputCapture
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtFile

/** Compares real class discovery across pool sizes and rejects mixed read generations. */
class MetroParallelSourceClassesTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
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

  fun testParallelClassDiscoveryPreservesOrderAndSpecialization() {
    val file =
      myFixture.configureMetroFile(
        """
      object First
      object Second
      object Third
      object Fourth
      @Inject class Holder<T>(val value: T)
      @DependencyGraph interface AppGraph {
        val first: Holder<First>
        val second: Holder<Second>
        val third: Holder<Third>
        val fourth: Holder<Fourth>
        val repeated: Holder<First>
      }
      """
      )
    val sequential = allowAnalysisOnEdt { processor(file).resolveInitial() }
    val progress = ConcurrentLinkedQueue<IndexBuildProgress>()
    val parallel = resolve(file, 4) { progress += it }

    assertEquivalent(sequential, parallel)
    val objects = parallel.addedBindings.map { it.typeKey.renderedType }
    assertTrue(
      objects.containsAll(listOf("test.First", "test.Second", "test.Third", "test.Fourth"))
    )
    assertEquals(
      parallel.addedBindings.size,
      parallel.addedBindings.map { it.typeKey }.distinct().size,
    )
    assertTrue(
      progress.any {
        it.workerFiles.any { file -> file?.name == "test.Holder" && file.path.endsWith(".kt") }
      }
    )
    val last = progress.last()
    assertEquals(0, last.activeWorkers)
    assertEquals(List(4) { null }, last.workerFiles)
    assertTrue(allowAnalysisOnEdt { parallel.dependencies.isCurrent() })
  }

  fun testSingleWorkerUsesTheSameSourceDiscoveryAsTheSynchronousEntryPoint() {
    val file =
      myFixture.configureMetroFile(
        """
      object Singleton
      @Inject class First(val singleton: Singleton)
      @Inject class Second(val first: First)
      @DependencyGraph interface AppGraph { val second: Second }
      """
      )
    val sequential = allowAnalysisOnEdt { processor(file).resolveInitial() }
    assertEquivalent(sequential, resolve(file, 1))
  }

  fun testMergingDifferentFileRevisionsCannotHideAStaleClassRead() {
    val file = myFixture.configureMetroFile("object First")
    val pointers = SmartPointerManager.getInstance(project)
    val first =
      SourceClassDependencies.Builder(pointers).apply { record(file, file.virtualFile) }.build()
    WriteCommandAction.runWriteCommandAction(project) {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      document.insertString(document.textLength, "\nobject Second")
      PsiDocumentManager.getInstance(project).commitDocument(document)
    }
    val second =
      SourceClassDependencies.Builder(pointers).apply { record(file, file.virtualFile) }.build()
    assertTrue(second.isCurrent())
    val combined =
      SourceClassDependencies.Builder(pointers)
        .apply {
          include(first)
          include(second)
        }
        .build()
    assertFalse(combined.isCurrent())
    val reversed =
      SourceClassDependencies.Builder(pointers)
        .apply {
          include(second)
          include(first)
        }
        .build()
    assertFalse(reversed.isCurrent())
    assertEquals(setOf(file.virtualFile), combined.owners[file.virtualFile])
  }

  fun testValidatedCacheDropsContextStampsAndRetainsDeclarationStamps() {
    val context = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val declaration =
      myFixture.addFileToProject("test/Dependency.kt", "package test; object Dependency")
    val pointers = SmartPointerManager.getInstance(project)
    val captured =
      SourceClassDependencies.Builder(pointers)
        .apply {
          recordContext(context)
          recordContext(declaration)
          record(declaration, context.virtualFile)
        }
        .build()
    assertTrue(captured.isCurrent())
    val cached = captured.withoutReadContexts()
    assertTrue(cached.isCurrent())
    assertFalse(cached.owners.containsKey(context.virtualFile))
    assertEquals(setOf(context.virtualFile), cached.owners[declaration.virtualFile])

    appendComment(context)
    assertFalse(captured.isCurrent())
    assertTrue("Read anchors belong only to the completed capture", cached.isCurrent())
    appendComment(declaration)
    assertFalse("A file used in both roles retains its declaration dependency", cached.isCurrent())
  }

  fun testDroppingContextStampsPreservesAConflictingReadGeneration() {
    val file = myFixture.configureMetroFile("object Dependency")
    val pointers = SmartPointerManager.getInstance(project)
    val context = SourceClassDependencies.Builder(pointers).apply { recordContext(file) }.build()
    appendComment(file)
    val declaration =
      SourceClassDependencies.Builder(pointers).apply { record(file, file.virtualFile) }.build()
    val combined =
      SourceClassDependencies.Builder(pointers)
        .apply {
          include(declaration)
          include(context)
        }
        .build()
    assertFalse(combined.isCurrent())
    assertFalse(combined.withoutReadContexts().isCurrent())
  }

  private fun appendComment(file: com.intellij.psi.PsiFile) {
    WriteCommandAction.runWriteCommandAction(project) {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      document.insertString(document.textLength, "\n// Unrelated edit")
      PsiDocumentManager.getInstance(project).commitDocument(document)
    }
  }

  /** Each processor starts from the raw shard so class specialization must actually run. */
  private fun processor(file: KtFile): SourceClassBindingPostProcessor {
    val shard = FileShardBuilder(project, file.metroIdeState().options).buildShard(file)
    val index =
      BindingIndexBuilder().apply {
        bindings += shard.bindings
        consumers += shard.consumers
        graphs += shard.graphs
        contributions += shard.contributions
        assistedSites += shard.assistedSites
        bindingContainers += shard.bindingContainers
        dynamicGraphs += shard.dynamicGraphs
      }
    ResolutionInputCapture(project) { _, _ -> }.capture(index, setOf(file.virtualFile))
    return SourceClassBindingPostProcessor(
      project,
      shard.bindings,
      shard.consumers,
      ConsumerOwnershipBundle.build(index.build()),
    )
  }

  private fun resolve(
    file: KtFile,
    poolSize: Int,
    publish: (IndexBuildProgress) -> Unit = {},
  ): SourceClassResolution {
    val future = CompletableFuture.supplyAsync {
      runBlocking {
        val processor = smartReadAction(project) { processor(file) }
        val executor =
          SnapshotReadExecutor(
            project,
            poolSize,
            IndexBuildProgressReporter(publish, updateIntervalNanos = 0),
            IndexBuildPhase.RESOLVING_CLASS_BINDINGS,
          )
        executor.run { processor.resolveInitial(it) }
      }
    }
    return PlatformTestUtil.waitForFuture(future, 30_000)
  }

  /** Ordering, module ownership, and budget results must match independently allocated bindings. */
  private fun assertEquivalent(expected: SourceClassResolution, actual: SourceClassResolution) {
    assertEquals(
      expected.addedBindings.map {
        it.typeKey to it.dependencies.map { dependency -> dependency.typeKey }
      },
      actual.addedBindings.map {
        it.typeKey to it.dependencies.map { dependency -> dependency.typeKey }
      },
    )
    assertEquals(expected.processedRequests, actual.processedRequests)
    assertEquals(expected.resolvedRequests, actual.resolvedRequests)
    assertEquals(expected.boundaryRequests.map { it.id }, actual.boundaryRequests.map { it.id })
    assertEquals(
      expected.useSites.mapValues { it.value.keys },
      actual.useSites.mapValues { it.value.keys },
    )
    assertEquals(expected.budget.derivedOrdinals, actual.budget.derivedOrdinals)
    assertEquals(expected.incompleteBindings, actual.incompleteBindings)
  }
}
