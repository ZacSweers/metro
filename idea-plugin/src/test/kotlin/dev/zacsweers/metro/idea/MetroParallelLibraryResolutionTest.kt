// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.LibraryIndexPostProcessor
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.SourceClassBindingPostProcessor
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.KaBinding
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

/** Binary requests keep their queue order and shared source expansion limits at every pool size. */
class MetroParallelLibraryResolutionTest : BasePlatformTestCase() {
  private var previousLibraryResolution = true

  override fun setUp() {
    super.setUp()
    val settings = MetroSettings.getInstance(project).state
    previousLibraryResolution = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  override fun tearDown() {
    try {
      MetroSettings.getInstance(project).state.resolveFromLibraries = previousLibraryResolution
      project.service<MetroResolutionService>().settingsChanged()
    } finally {
      super.tearDown()
    }
  }

  fun testParallelBinaryRequestsPreserveSourceTransitionsAndBindingOrder() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
        import libtest.LibClientWithDeps
        import libtest.LibGenericAssistedExample
        import libtest.LibRetargetedDependencyB

        @AssistedInject
        class SourceExample<T>(@Assisted val id: String, val dependency: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): SourceExample<T>
          }
        }

        @Inject
        class Requests(
          val first: LibClientWithDeps,
          val factory: LibGenericAssistedExample.Factory<SourceExample.Factory<LibClientWithDeps>>,
          val second: LibRetargetedDependencyB,
          val duplicate: LibClientWithDeps,
        )
        """
        )
      val sourceIndex = project.service<MetroResolutionService>().awaitIndex(file)
      val sequential = resolveLibraries(file, sourceIndex, parallelism = 1)
      val parallel = resolveLibraries(file, sourceIndex, parallelism = 4)
      assertEquals(sequential.bindings, parallel.bindings)
      assertEquals(sequential.incomplete, parallel.incomplete)
      val keys = parallel.bindings.map { it.first }
      assertTrue(keys.contains("test.SourceExample.Factory<libtest.LibClientWithDeps>"))
      assertEquals(1, keys.count { it == "libtest.LibClientWithDeps" })
      assertEquals(1, keys.count { it == "libtest.LibHttpClient" })
      assertTrue(keys.contains("libtest.LibRetargetedDependencyB"))
      val workerProgress = parallel.progress.filter { it.workerFiles.any { file -> file != null } }
      assertTrue(workerProgress.isNotEmpty())
      assertTrue(workerProgress.all { it.workerLimit == 4 })
      assertTrue(
        workerProgress.any { event ->
          event.workerFiles.any { it?.name == "libtest.LibClientWithDeps" }
        }
      )
      assertEquals(listOf(null, null, null, null), parallel.progress.last().workerFiles)
    }
  }

  fun testParallelLibraryRequestsPreserveGenericExpansionBoundaries() {
    module.withMetroLibFixtureLibrary {
      module.addKotlinStdlibLibrary()
      val file =
        myFixture.configureMetroFile(
          """
        import libtest.LibGrowingNode
        import libtest.LibRetargetedDependencyB

        @Inject
        class Requests(
          val first: LibGrowingNode<String>,
          val second: LibGrowingNode<Int>,
          val concrete: LibRetargetedDependencyB,
        )
        """
        )
      val sourceIndex = project.service<MetroResolutionService>().awaitIndex(file)
      val sequential = resolveLibraries(file, sourceIndex, parallelism = 1)
      val parallel = resolveLibraries(file, sourceIndex, parallelism = 4)
      assertFalse(sequential.incomplete.isEmpty())
      assertEquals(sequential.bindings, parallel.bindings)
      assertEquals(sequential.incomplete, parallel.incomplete)
      assertTrue(parallel.bindings.any { it.first == "libtest.LibRetargetedDependencyB" })
    }
  }

  fun testDeletingHintUseSiteBeforeLookupInvalidatesCapturedSeeds() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
        import libtest.LibClientWithDeps

        @DependencyGraph
        interface AppGraph {
          val client: LibClientWithDeps
        }
        """
        )
      val settings = MetroSettings.getInstance(project).state
      settings.resolveFromLibraries = true
      val service = project.service<MetroResolutionService>()
      service.settingsChanged()
      val index = service.awaitIndex(file)
      val client =
        index.bindings.filterIsInstance<KaBinding.ConstructorInjected>().single {
          it.typeKey.renderedType == "libtest.LibClientWithDeps"
        }
      val graphs = index.graphs.filter { it.pointer.virtualFile == file.virtualFile }
      assertEquals(1, graphs.size)
      settings.automaticallyRefreshGraphData = false
      service.settingsChanged()
      val drained = CompletableFuture.runAsync { runBlocking { service.awaitCoordinatorBarrier() } }
      PlatformTestUtil.waitForFuture(drained, 30_000)

      val captured = CompletableFuture<Unit>()
      val release = CountDownLatch(1)
      val paused = AtomicBoolean()
      val preparation = CompletableFuture.supplyAsync {
        runBlocking {
          val progress =
            IndexBuildProgressReporter(
              updateIntervalNanos = 0,
              publish = { event ->
                val lookupScheduled = checkNotNull(event.total) > 0 && event.activeWorkers == 0
                if (lookupScheduled && paused.compareAndSet(false, true)) {
                  assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                  captured.complete(Unit)
                  check(release.await(30, TimeUnit.SECONDS)) { "The test did not release lookup" }
                }
              },
            )
          SnapshotReadExecutor(project, 2, progress, IndexBuildPhase.RESOLVING_LIBRARY_CLASSES)
            .run { executor ->
              val processor = executor.read {
                val ownership = ConsumerOwnershipBundle.build(index)
                val bindings = mutableListOf<KaBinding>(client)
                // Hint-created providers can seed a lookup from a graph with no source consumers.
                // Start with no source captures so the selected seed must retain its own file
                // stamp.
                val initial =
                  SourceClassBindingPostProcessor(
                      project,
                      bindings,
                      emptyList(),
                      ownership,
                    )
                    .snapshot()
                LibraryIndexPostProcessor(
                  project,
                  file.metroIdeState().options,
                  bindings,
                  emptyList(),
                  graphs,
                  emptyList(),
                  initial.classUseSites,
                  ownership,
                  initial,
                )
              }
              val resolved = processor.postProcess(executor)
              executor.read { resolved.dependencies.isCurrent() }
            }
        }
      }
      try {
        PlatformTestUtil.waitForFuture(captured, 30_000)
        WriteCommandAction.runWriteCommandAction(project) { file.declarations.single().delete() }
        release.countDown()
        assertFalse(PlatformTestUtil.waitForFuture(preparation, 30_000))
      } finally {
        release.countDown()
        PlatformTestUtil.waitForFuture(preparation, 30_000)
      }
    }
  }

  /** Exercise the same suspend entry points as a refresh without rebuilding source file shards. */
  private fun resolveLibraries(
    file: KtFile,
    sourceIndex: BindingIndex,
    parallelism: Int,
  ): Resolution {
    val events = ConcurrentLinkedQueue<IndexBuildProgress>()
    val result = CompletableFuture.supplyAsync {
      runBlocking {
        val executor =
          SnapshotReadExecutor(
            project,
            parallelism,
            IndexBuildProgressReporter(events::add, updateIntervalNanos = 0),
            IndexBuildPhase.RESOLVING_LIBRARY_CLASSES,
          )
        executor.run {
          val ownership = executor.read { ConsumerOwnershipBundle.build(sourceIndex) }
          val source =
            executor
              .read {
                SourceClassBindingPostProcessor(
                  project,
                  sourceIndex.bindings,
                  sourceIndex.consumers,
                  ownership,
                )
              }
              .resolveInitial(executor)
          val bindings = (sourceIndex.bindings + source.addedBindings).toMutableList()
          val processor = executor.read {
            LibraryIndexPostProcessor(
              project,
              file.metroIdeState().options,
              bindings,
              sourceIndex.consumers,
              sourceIndex.graphs,
              sourceIndex.contributions,
              source.classUseSites,
              ownership,
              source,
            )
          }
          val resolved = processor.postProcess(executor)
          Resolution(
            bindings.map { binding ->
              binding.typeKey.renderedType to binding.dependencies.map { it.typeKey.renderedType }
            },
            resolved.incompleteBindings.values.flatMap { entries ->
              entries.map { (identity, reason) -> identity.key.renderedType to reason }
            },
            events,
          )
        }
      }
    }
    return PlatformTestUtil.waitForFuture(result, 30_000)
  }

  private data class Resolution(
    val bindings: List<Pair<String, List<String>>>,
    val incomplete: List<Pair<String, String>>,
    val progress: Collection<IndexBuildProgress>,
  )
}
