// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.GraphPath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/** Focused publication and anchor-maintenance coverage for file presentation bundles. */
class MetroFilePresentationTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    val service = project.service<MetroResolutionService>()
    MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = true
    service.settingsChanged()
    awaitCoordinator(service)
    service.resetGraphBrowserActivation()
  }

  fun testPresentationReverseUsageRetainsNegativePinnedContextAnswers() {
    val file =
      myFixture.configureMetroFile(
        """
        abstract class OtherScope

        interface Repo

        @Inject
        @ContributesBinding(AppScope::class)
        class AppRepo : Repo

        @Inject
        @ContributesBinding(OtherScope::class)
        class OtherRepo : Repo

        @Inject class Consumer(val repo: Repo)

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val consumer: Consumer
        }

        @DependencyGraph(OtherScope::class)
        interface OtherGraph {
          val consumer: Consumer
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val index = service.awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val presentation =
      checkNotNull(file.awaitMetroPresentation().declaration(declarations.klass("AppRepo")))
    val reverseUsage = checkNotNull(presentation.reverseUsage)
    val contexts =
      index.graphs.associate { graph -> graph.name to index.contextsFor(graph).single().path }

    fun consumerNames(path: GraphPath?): List<String?> {
      return reverseUsage.consumersFor(path).map { consumer ->
        (consumer.pointer.element as? KtNamedDeclaration)?.name
      }
    }

    assertEquals(listOf("repo"), consumerNames(null))
    assertEquals(listOf("repo"), consumerNames(contexts.getValue("AppGraph")))
    assertTrue(consumerNames(contexts.getValue("OtherGraph")).isEmpty())
  }

  fun testPresentationAnchorsFollowBodyEditsAndReorderingUntilManualRefresh() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject
        class First {
          fun label(): String = "before"
        }

        @Inject class Second
        """
      )
    val service = project.service<MetroResolutionService>()
    val initialIndex = service.awaitIndex(file)
    val initialBundle = file.awaitMetroPresentation()
    val initialDeclarations = file.declarationsIncludingNested()
    val firstPresentation =
      checkNotNull(initialBundle.declaration(initialDeclarations.klass("First")))
    val secondPresentation =
      checkNotNull(initialBundle.declaration(initialDeclarations.klass("Second")))
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()

    try {
      file.updateDocument { document ->
        val valueStart = document.text.indexOf("\"before\"")
        document.replaceString(valueStart, valueStart + "\"before\"".length, "\"after value\"")
        val firstStart = document.text.indexOf("@Inject\nclass First")
        document.insertString(firstStart, "// shifted anchor\n")
      }
      val bodyEdited = file.awaitMetroPresentation()
      val bodyEditedDeclarations = file.declarationsIncludingNested()
      assertTrue(bodyEdited.sharesSemanticData(initialBundle))
      assertSame(
        firstPresentation,
        bodyEdited.declaration(bodyEditedDeclarations.klass("First")),
      )
      assertSame(
        secondPresentation,
        bodyEdited.declaration(bodyEditedDeclarations.klass("Second")),
      )

      file.updateDocument { document ->
        val firstStart = document.text.indexOf("// shifted anchor")
        val secondStart = document.text.indexOf("@Inject class Second")
        val firstBlock = document.text.substring(firstStart, secondStart).trim()
        val secondBlock = document.text.substring(secondStart).trim()
        document.replaceString(
          firstStart,
          document.textLength,
          "$secondBlock\n\n$firstBlock",
        )
      }
      val reordered = file.awaitMetroPresentation()
      val reorderedDeclarations = file.declarationsIncludingNested()
      assertTrue(reordered.sharesSemanticData(initialBundle))
      val reorderedFirst = reordered.declaration(reorderedDeclarations.klass("First"))
      val reorderedSecond = reordered.declaration(reorderedDeclarations.klass("Second"))
      assertTrue(
        "Reordering must not attach Second's presentation to First",
        reorderedFirst == null || reorderedFirst === firstPresentation,
      )
      assertTrue(
        "Reordering must not attach First's presentation to Second",
        reorderedSecond == null || reorderedSecond === secondPresentation,
      )

      file.updateDocument { document ->
        document.insertString(document.textLength, "\n\n@Inject class AddedLater")
      }
      val frozen = file.awaitMetroPresentation()
      val added = file.declarationsIncludingNested().klass("AddedLater")
      assertTrue(frozen.sharesSemanticData(initialBundle))
      assertNull(frozen.declaration(added))
      assertSame(initialIndex, service.presentationIndex(file))

      val refreshed = CompletableFuture<Unit>()
      service.addIndexListener(testRootDisposable) {
        val addedPublished =
          service.presentationIndex(file).bindings.any { binding ->
            binding.implementationName == "AddedLater"
          }
        if (!service.isManualGraphDataRefreshRequired && addedPublished) {
          refreshed.complete(Unit)
        }
      }
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(refreshed, 30_000)

      val refreshedBundle = file.awaitMetroPresentation()
      assertFalse(refreshedBundle.sharesSemanticData(initialBundle))
      assertNotNull(refreshedBundle.declaration(added))
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      awaitCoordinator(service)
    }
  }

  fun testColdPresentationAnchorsRejectEqualLengthReplacementAndDeletion() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Original

        @Inject class Removed

        @Inject class Stable
        """
      )
    val service = project.service<MetroResolutionService>()
    val initialIndex = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    awaitCoordinator(service)

    try {
      // Build the first presentation from frozen semantics after its source declarations change.
      file.updateDocument { document ->
        val originalStart = document.text.indexOf("Original")
        document.replaceString(
          originalStart,
          originalStart + "Original".length,
          "Replaced",
        )
        val removedStart = document.text.indexOf("@Inject class Removed")
        val removedEnd = removedStart + "@Inject class Removed\n\n".length
        document.deleteString(removedStart, removedEnd)
      }

      val updated = file.awaitMetroPresentation()
      val currentDeclarations = file.declarationsIncludingNested()
      val replaced = currentDeclarations.klass("Replaced")
      val stable = currentDeclarations.klass("Stable")

      assertSame(initialIndex.generationToken, updated.generationToken)
      assertNull(updated.declaration(replaced))
      val stablePresentation = updated.declaration(stable)
      if (stablePresentation != null) {
        assertEquals(
          listOf("Stable"),
          stablePresentation.bindingEntries.mapNotNull { it.implementationName }.distinct(),
        )
      }
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      awaitCoordinator(service)
    }
  }

  fun testRepeatedPresentationRequestsReuseCurrentAnchors() {
    val file = myFixture.configureMetroFile("@Inject class Target")
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    val initialBundle = file.awaitMetroPresentation()
    val initialPresentation =
      checkNotNull(initialBundle.declaration(file.declarationsIncludingNested().klass("Target")))
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    awaitCoordinator(service)

    try {
      file.updateDocument { document -> document.insertString(0, "// offset\n") }
      val target = file.declarationsIncludingNested().klass("Target")
      assertNull(initialBundle.declaration(target))
      repeat(8) {
        val requested = checkNotNull(service.presentationBundle(target))
        assertTrue(requested.sharesSemanticData(initialBundle))
      }

      val updated = file.awaitMetroPresentation()
      assertSame(initialPresentation, updated.declaration(target))
      repeat(8) { assertSame(updated, service.presentationBundle(target)) }
      awaitCoordinator(service)
      assertSame(updated, file.awaitMetroPresentation())
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      awaitCoordinator(service)
    }
  }

  fun testPresentationAnchorsFollowTheLatestOfRepeatedEdits() {
    val file = myFixture.configureMetroFile("@Inject class Target")
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    val initialBundle = file.awaitMetroPresentation()
    val initialPresentation =
      checkNotNull(initialBundle.declaration(file.declarationsIncludingNested().klass("Target")))
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    awaitCoordinator(service)

    try {
      repeat(8) { edit ->
        file.updateDocument { document -> document.insertString(0, "// edit $edit\n") }
        val target = file.declarationsIncludingNested().klass("Target")
        val requested = checkNotNull(service.presentationBundle(target))
        assertTrue(requested.sharesSemanticData(initialBundle))
      }

      val updated = file.awaitMetroPresentation()
      val target = file.declarationsIncludingNested().klass("Target")
      assertTrue(updated.anchorsAreCurrent(file.modificationStamp))
      assertTrue(updated.sharesSemanticData(initialBundle))
      assertSame(initialPresentation, updated.declaration(target))
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      awaitCoordinator(service)
    }
  }

  fun testDisposalDiscardsQueuedPresentationAnchorRequests() {
    val file = myFixture.configureMetroFile("@Inject class Target")
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val service = MetroResolutionService(project, serviceScope)
    val settings = MetroSettings.getInstance(project).state
    val projectService = project.service<MetroResolutionService>()
    val paused = CompletableFuture<Unit>()
    val release = CountDownLatch(1)

    try {
      service.awaitIndex(file)
      val initialBundle = file.awaitMetroPresentation(service)
      settings.automaticallyRefreshGraphData = false
      service.settingsChanged()
      projectService.settingsChanged()
      awaitCoordinator(service)
      awaitCoordinator(projectService)

      // Hold the service's executor so its next ordinary presentation request remains queued.
      executor.submit {
        paused.complete(Unit)
        release.await()
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)
      file.updateDocument { document -> document.insertString(0, "// dispose\n") }
      val target = file.declarationsIncludingNested().klass("Target")
      assertSame(initialBundle, service.presentationBundle(target))

      Disposer.dispose(service)
      release.countDown()
      // JUnit 3 discovers zero-argument void functions generated for empty Runnable lambdas.
      val drained = CompletableFuture.supplyAsync({ Unit }, executor)
      PlatformTestUtil.waitForFuture(drained, 30_000)

      assertNull(service.presentationBundle(target))
    } finally {
      release.countDown()
      if (!Disposer.isDisposed(service)) Disposer.dispose(service)
      serviceScope.cancel()
      dispatcher.close()
      settings.automaticallyRefreshGraphData = true
      projectService.settingsChanged()
      awaitCoordinator(projectService)
    }
  }

  private fun KtFile.updateDocument(update: (Document) -> Unit) {
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(this))
    WriteCommandAction.runWriteCommandAction(project) { update(document) }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  private fun awaitCoordinator(service: MetroResolutionService) {
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
  }
}
