// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.FileShardBuilder
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.LibraryGraphDiscovery
import dev.zacsweers.metro.idea.index.LibraryGraphMetadata
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.snapshot.SnapshotReadExecutor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.psi.KtFile

/** Real binary hints retain scope deduplication, graph order, and source owners across pools. */
class MetroParallelMetadataTest : BasePlatformTestCase() {
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

  fun testParallelHintsPreserveScopesVisibilityAndGraphFrontiers() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
        import libtest.LibChildGraph
        import libtest.LibDual
        import libtest.LibParentScope
        import libtest.LibScope

        @DependencyGraph(AppScope::class, additionalScopes = [LibParentScope::class])
        interface AppGraph {
          val dual: LibDual
          val child: LibChildGraph.Factory
        }

        @DependencyGraph(LibScope::class)
        interface OtherGraph {
          val dual: LibDual
        }
        """
        )
      val sequential = discover(listOf(file), parallelism = 1)
      val parallel = discover(listOf(file), parallelism = 4)
      assertEquals(sequential.signature, parallel.signature)
      val declarations = parallel.metadata.declarations.graphs.map { it.name }
      assertEquals(1, declarations.count { it == "LibChildGraph" })
      assertEquals(1, declarations.count { it == "LibGrandchildGraph" })
      assertFalse(declarations.contains("LibHiddenChildGraph"))
      val contributions = parallel.metadata.contributions.contributions
      assertFalse(contributions.any { it.classId?.shortClassName?.asString() == "LibHiddenImpl" })
      val dualScopes =
        contributions
          .filter {
            it.classId?.shortClassName?.asString() == "LibDualImpl"
          }
          .flatMap { it.scopeKeys }
          .map { it.asSingleFqName().asString() }
      assertEquals(setOf("dev.zacsweers.metro.AppScope", "libtest.LibScope"), dualScopes.toSet())
      assertEquals(2, dualScopes.size)
      val active = parallel.progress.filter { it.workerFiles.any { file -> file != null } }
      assertTrue(active.isNotEmpty())
      assertTrue(active.all { it.workerLimit == 4 })
      assertTrue(
        active.any { event ->
          event.workerFiles.any { it?.name == "libtest.LibChildGraph.Factory" }
        }
      )
      assertEquals(listOf(null, null, null, null), parallel.progress.last().workerFiles)
    }
  }

  fun testParallelGraphRequestsRetainEverySourceDependencyOwner() {
    module.withMetroLibFixtureLibrary {
      val declarations =
        myFixture.addFileToProject(
          "test/ChildMembers.kt",
          """
          package test
          import dev.zacsweers.metro.ContributesTo
          import libtest.LibChildScope

          @ContributesTo(LibChildScope::class)
          interface ChildMembers { val extra: String }
          """
            .trimIndent(),
        ) as KtFile
      val owners =
        listOf("First", "Second").map { name ->
          myFixture.addFileToProject(
            "test/${name}Graph.kt",
            """
          package test
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibChildGraph
          import libtest.LibParentScope

          @DependencyGraph(LibParentScope::class)
          interface ${name}Graph { val child: LibChildGraph.Factory }
          """
              .trimIndent(),
          ) as KtFile
        }
      val sequential = discover(owners + declarations, parallelism = 1)
      val parallel = discover(owners + declarations, parallelism = 4)
      assertEquals(sequential.signature, parallel.signature)
      assertEquals(
        sequential.metadata.sourceDependencies.owners,
        parallel.metadata.sourceDependencies.owners,
      )
      assertEquals(
        owners.mapTo(linkedSetOf()) { it.virtualFile },
        parallel.metadata.sourceDependencies.owners.getValue(declarations.virtualFile),
      )
      assertEquals(1, parallel.metadata.declarations.graphs.count { it.name == "LibChildGraph" })
    }
  }

  fun testEmptyHintDiscoveryRetainsItsSourceContextStamp() {
    val file =
      myFixture.addFileToProject(
        "test/EmptyHints.kt",
        """
        package test
        import dev.zacsweers.metro.DependencyGraph

        abstract class LocalScope
        @DependencyGraph(LocalScope::class)
        interface EmptyGraph
        """
          .trimIndent(),
      ) as KtFile
    val discovered = discover(listOf(file), parallelism = 4)
    assertTrue(discovered.metadata.contributions.contributions.isEmpty())
    assertTrue(discovered.metadata.declarations.graphs.isEmpty())
    val dependencies = discovered.metadata.sourceDependencies
    val cachedDependencies = dependencies.withoutReadContexts()
    assertTrue(dependencies.owners.isEmpty())
    assertTrue(dependencies.isCurrent())
    WriteCommandAction.runWriteCommandAction(project) { file.delete() }
    assertFalse(dependencies.isCurrent())
    assertTrue(cachedDependencies.isCurrent())
  }

  /** Builds fresh source shards and exercises the suspend metadata entry point directly. */
  private fun discover(files: List<KtFile>, parallelism: Int): Discovery {
    val progress = ConcurrentLinkedQueue<IndexBuildProgress>()
    val future = CompletableFuture.supplyAsync {
      runBlocking {
        val executor =
          SnapshotReadExecutor(
            project,
            parallelism,
            IndexBuildProgressReporter(progress::add, updateIntervalNanos = 0),
            IndexBuildPhase.READING_DEPENDENCY_METADATA,
          )
        val metadata = executor.run {
          val discovery = executor.read {
            val options = files.first().metroIdeState().options
            val shards = files.map { FileShardBuilder(project, options).buildShard(it) }
            LibraryGraphDiscovery(
              project,
              options,
              shards.flatMap { it.graphs },
              shards.flatMap { it.contributions },
              shards.flatMap { it.consumers },
              shards.flatMap { it.graphInterfaces },
            )
          }
          discovery.discover(executor)
        }
        val signature = buildList {
          for (contribution in metadata.contributions.contributions) {
            add(
              "contribution:${contribution.classId}:${contribution.scopeKeys}:${contribution.kind}"
            )
          }
          for (graph in metadata.declarations.graphs) {
            add("graph:${graph.classId}:${graph.scopeKeys}:${graph.extensionCreations}")
          }
          for (binding in metadata.contributions.bindings + metadata.declarations.bindings) {
            val dependencies = binding.dependencies.map { it.typeKey.renderedType }
            add("binding:${binding.typeKey.renderedType}:${binding.label}:$dependencies")
          }
          for (consumer in metadata.declarations.consumers) {
            add("consumer:${consumer.key.renderedType}")
          }
        }
        Discovery(metadata, signature, progress)
      }
    }
    return PlatformTestUtil.waitForFuture(future, 30_000)
  }

  private class Discovery(
    val metadata: LibraryGraphMetadata,
    val signature: List<String>,
    val progress: Collection<IndexBuildProgress>,
  )
}
