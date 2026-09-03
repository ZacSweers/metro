// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.graph.auto.MetroPinnedGraphValidationService
import dev.zacsweers.metro.idea.index.AutomaticRefreshWindow
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.GraphPath
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Covers opt-in scheduling, pin ownership, and cancellation through the real validation service.
 */
class MetroPinnedGraphValidationTest : BasePlatformTestCase() {
  private var previousAutomaticRefresh = true
  private var previousAutomaticValidation = false

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    val settings = MetroSettings.getInstance(project).state
    previousAutomaticRefresh = settings.automaticallyRefreshGraphData
    previousAutomaticValidation = settings.automaticallyValidatePinnedGraph
    settings.automaticallyValidatePinnedGraph = false
    project.enableImmediateAutomaticRefresh()
    project.service<MetroGraphValidationService>().clearResults()
    project.service<GraphContextPinService>().clear()
  }

  override fun tearDown() {
    try {
      MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = false
      project.service<GraphContextPinService>().clear()
      project.service<MetroPinnedGraphValidationService>().requestValidation()
      project.service<MetroGraphValidationService>().setBeforeGraphSealObserver(null)
      val settings = MetroSettings.getInstance(project).state
      settings.automaticallyRefreshGraphData = previousAutomaticRefresh
      settings.automaticallyValidatePinnedGraph = previousAutomaticValidation
    } finally {
      super.tearDown()
    }
  }

  fun testAutomaticValidationRequiresOptInAndAutomaticRefresh() {
    val (_, context) = configureGraph()
    project.service<GraphContextPinService>().pin(context.path)
    val automatic = project.service<MetroPinnedGraphValidationService>()
    assertNull(automatic.requestValidation())

    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyValidatePinnedGraph = true
    settings.automaticallyRefreshGraphData = false
    assertNull(automatic.requestValidation())
    assertEmpty(project.service<MetroGraphValidationService>().retainedResults())
  }

  fun testAutomaticValidationWaitsForPublishedGraphRefresh() {
    val (file, context) = configureGraph()
    val resolution = project.service<MetroResolutionService>()
    val automatic = project.service<MetroPinnedGraphValidationService>()
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    project.service<GraphContextPinService>().pin(context.path)

    WriteCommandAction.runWriteCommandAction(project) {
      addMissingStringProvider(file)
      assertTrue(resolution.isGraphDataRefreshRequired)
      assertNull(automatic.requestValidation())
    }
    assertEmpty(project.service<MetroGraphValidationService>().retainedResults())

    val result = awaitCurrentResult(context.path) { resolution.refreshGraphData() }
    assertEmpty(result.diagnostics)
    assertFalse(resolution.isGraphDataRefreshRequired)
  }

  fun testIrrelevantEditResumesAutomaticValidationWithTheSameIndex() {
    val unrelated =
      myFixture.addFileToProject("test/Unrelated.kt", "package test\n\nclass Unrelated") as KtFile
    val (file, context) = configureGraph()
    val resolution = project.service<MetroResolutionService>()
    val initialIndex = resolution.cachedIndex(file)
    val clock = AtomicLong()
    resolution.setAutomaticRefreshWindowForTest(AutomaticRefreshWindow(1_000, 0, clock::get))
    val validation = project.service<MetroGraphValidationService>()
    val seals = AtomicInteger()
    val ready = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    validation.setBeforeGraphSealObserver {
      if (seals.incrementAndGet() == 1) {
        ready.complete(Unit)
        release.await()
      }
    }
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    project.service<GraphContextPinService>().pin(context.path)

    try {
      PlatformTestUtil.waitForFuture(ready, 30_000)
      val stale = CompletableFuture<Unit>()
      resolution.addIndexListener(testRootDisposable) {
        if (resolution.isGraphDataRefreshRequired) stale.complete(Unit)
      }
      awaitValidationStops(context.path) {
        WriteCommandAction.runWriteCommandAction(project) {
          (unrelated.declarations.single() as KtNamedDeclaration).setName("StillUnrelated")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        PlatformTestUtil.waitForFuture(stale, 30_000)
        release.countDown()
      }
      assertTrue(resolution.isGraphDataRefreshRequired)
      assertEmpty(validation.retainedResults())

      val result =
        awaitCurrentResult(context.path) {
          clock.set(1_000)
          resolution.wakeAutomaticRefreshForTest()
        }
      assertEquals(context.path, result.context.path)
      assertSame(initialIndex, resolution.cachedIndex(file))
      assertEquals(2, seals.get())
      assertFalse(resolution.isGraphDataRefreshRequired)
    } finally {
      release.countDown()
      project.service<GraphContextPinService>().clear()
      resolution.setAutomaticRefreshWindowForTest(AutomaticRefreshWindow(0, 0))
    }
  }

  fun testCachedOnlyCaptureCancelsWhileExplicitValidationCanRefresh() {
    val (file, context) = configureGraph()
    val resolution = project.service<MetroResolutionService>()
    val validation = project.service<MetroGraphValidationService>()
    val seals = AtomicInteger()
    validation.setBeforeGraphSealObserver { seals.incrementAndGet() }
    MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
    resolution.settingsChanged()
    WriteCommandAction.runWriteCommandAction(project) { addMissingStringProvider(file) }
    runBlocking { withTimeout(30_000) { resolution.awaitCoordinatorBarrier() } }
    assertSame(BindingIndex.EMPTY, resolution.cachedIndex(file))

    var delivered = false
    val automatic =
      validation.validateWithExtensionsAsync(
        context,
        showProgress = false,
        allowIndexBuild = false,
      ) {
        delivered = true
      }
    awaitCompletion(automatic)
    assertTrue(automatic.isCancelled)
    assertFalse(delivered)
    assertEquals(0, seals.get())
    assertEmpty(validation.retainedResults())
    assertSame(BindingIndex.EMPTY, resolution.cachedIndex(file))

    lateinit var explicit: Job
    val result =
      awaitCurrentResult(context.path) {
        explicit = validation.validateWithExtensionsAsync(context, showProgress = false) {}
      }
    awaitCompletion(explicit)
    assertEmpty(result.diagnostics)
    assertEquals(1, seals.get())
    assertTrue(resolution.isCurrent(resolution.cachedIndex(file)))
  }

  fun testUnpinCancelsThePendingDebounce() {
    val (_, context) = configureGraph()
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    val pin = project.service<GraphContextPinService>()
    pin.pin(context.path)
    val pending =
      checkNotNull(project.service<MetroPinnedGraphValidationService>().requestValidation())
    pin.clear()
    awaitCompletion(pending)
    assertTrue(pending.isCancelled)
    assertEmpty(project.service<MetroGraphValidationService>().retainedResults())
  }

  fun testAutomaticValidationUsesThePinnedExtensionPath() {
    val file =
      myFixture.configureMetroFile(
        """
      @GraphExtension interface Child { val value: String }
      @DependencyGraph interface First {
        val child: Child
        @Provides fun value(): String = "first"
      }
      @DependencyGraph interface Second { val child: Child }
      """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val child = index.graphs.single { it.name == "Child" }
    val context = index.contextsFor(child).single { it.rootGraph.name == "First" }
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    awaitCurrentResult(context.path) {
      project.service<GraphContextPinService>().pin(context.path)
    }
    val retained = project.service<MetroGraphValidationService>().retainedResults()
    assertEquals(listOf(context.path), retained.map { it.result.context.path })
    assertFalse(retained.single().stale)
  }

  fun testRepeatedRequestsReuseTheCurrentResult() {
    val (_, context) = configureGraph()
    val validation = project.service<MetroGraphValidationService>()
    val seals = AtomicInteger()
    validation.setBeforeGraphSealObserver { seals.incrementAndGet() }
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    val first =
      awaitCurrentResult(context.path) {
        project.service<GraphContextPinService>().pin(context.path)
      }
    val completedSeals = seals.get()
    val automatic = project.service<MetroPinnedGraphValidationService>()
    awaitCompletion(checkNotNull(automatic.requestValidation()))
    assertSame(first, validation.retainedResults().single().result)
    assertEquals(completedSeals, seals.get())
  }

  fun testTurningOffAutomaticRefreshCancelsASeal() {
    val (_, context) = configureGraph()
    val validation = project.service<MetroGraphValidationService>()
    val ready = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    validation.setBeforeGraphSealObserver {
      ready.complete(Unit)
      release.await()
    }
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    project.service<GraphContextPinService>().pin(context.path)
    val automatic = project.service<MetroPinnedGraphValidationService>()
    try {
      PlatformTestUtil.waitForFuture(ready, 30_000)
      awaitValidationStops(context.path) {
        MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
        assertNull(automatic.requestValidation())
        release.countDown()
      }
      assertEmpty(validation.retainedResults())
    } finally {
      release.countDown()
      MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
      automatic.requestValidation()
    }
  }

  fun testExplicitValidationReplacesAnAutomaticSeal() {
    val (_, context) = configureGraph()
    val validation = project.service<MetroGraphValidationService>()
    val automaticReady = CompletableFuture<Unit>()
    val releaseAutomatic = CountDownLatch(1)
    val seals = AtomicInteger()
    validation.setBeforeGraphSealObserver {
      if (seals.incrementAndGet() == 1) {
        automaticReady.complete(Unit)
        releaseAutomatic.await()
      }
    }
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    project.service<GraphContextPinService>().pin(context.path)
    var explicit: Job? = null
    try {
      PlatformTestUtil.waitForFuture(automaticReady, 30_000)
      val delivered = CompletableFuture<List<KaGraphValidationResult>>()
      val request =
        validation.validateWithExtensionsAsync(context, showProgress = false) {
          delivered.complete(it)
        }
      explicit = request
      request.invokeOnCompletion { failure ->
        if (failure != null) delivered.completeExceptionally(failure)
      }
      val result = PlatformTestUtil.waitForFuture(delivered, 30_000).single().requireCompleted()
      awaitCompletion(request)
      assertSame(result, validation.retainedResults().single().result)
      assertEquals(2, seals.get())
    } finally {
      releaseAutomatic.countDown()
      explicit?.cancel()
      project.service<GraphContextPinService>().clear()
    }
  }

  fun testSourceEditRevalidatesThePinnedGraph() {
    val (file, context) = configureGraph()
    MetroSettings.getInstance(project).state.automaticallyValidatePinnedGraph = true
    val first =
      awaitCurrentResult(context.path) {
        project.service<GraphContextPinService>().pin(context.path)
      }
    val result =
      awaitCurrentResult(context.path, previous = first) {
        WriteCommandAction.runWriteCommandAction(project) {
          val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
          val offset = document.text.indexOf("String")
          document.replaceString(offset, offset + "String".length, "Int")
          PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
      }
    assertEquals(context.path, result.context.path)
  }

  private fun configureGraph(): Pair<KtFile, GraphContext> {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    return file to index.contextsFor(index.graphs.single()).single()
  }

  /**
   * Makes the edited graph valid so a successful result proves capture used its refreshed inputs.
   */
  private fun addMissingStringProvider(file: KtFile) {
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    document.setText(
      document.text.replace(
        "val value: String",
        "val value: String\n@Provides fun provideString(): String = \"ready\"",
      )
    )
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  /** Index publication can replace a debounce job, so success is tied to the accepted result. */
  private fun awaitCurrentResult(
    path: GraphPath,
    previous: KaGraphValidationResult.Completed? = null,
    trigger: () -> Unit,
  ): KaGraphValidationResult.Completed {
    val validation = project.service<MetroGraphValidationService>()
    val completed = CompletableFuture<KaGraphValidationResult.Completed>()
    fun checkResult() {
      val current =
        validation.retainedResults().singleOrNull { it.result.context.path == path } ?: return
      if (current.stale || current.result === previous) return
      val result = current.result as? KaGraphValidationResult.Completed ?: return
      completed.complete(result)
    }
    validation.addResultListener(testRootDisposable, ::checkResult)
    trigger()
    checkResult()
    return PlatformTestUtil.waitForFuture(completed, 30_000)
  }

  /** Waits for the running validation's terminal state after the test changes its owner. */
  private fun awaitValidationStops(path: GraphPath, trigger: () -> Unit) {
    val validation = project.service<MetroGraphValidationService>()
    assertTrue(validation.isValidationRunning(path))
    val stopped = CompletableFuture<Unit>()
    validation.addValidationProgressListener(testRootDisposable) {
      if (!validation.isValidationRunning(path)) stopped.complete(Unit)
    }
    trigger()
    PlatformTestUtil.waitForFuture(stopped, 30_000)
    assertFalse(validation.isValidationRunning(path))
  }

  private fun awaitCompletion(job: Job) {
    val completed = CompletableFuture<Unit>()
    job.invokeOnCompletion { failure ->
      if (failure == null || job.isCancelled) completed.complete(Unit)
      else completed.completeExceptionally(failure)
    }
    PlatformTestUtil.waitForFuture(completed, 30_000)
  }
}
