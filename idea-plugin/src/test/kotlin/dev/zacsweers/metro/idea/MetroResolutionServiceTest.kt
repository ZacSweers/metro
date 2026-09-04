// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import androidx.tracing.wire.TraceDriver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.xml.XmlFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.WaitFor
import com.intellij.util.ui.UIUtil
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.AutomaticRefreshWindow
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.IndexBuildProgressReporter
import dev.zacsweers.metro.idea.index.IndexRequestMode
import dev.zacsweers.metro.idea.index.IndexRequestPolicy
import dev.zacsweers.metro.idea.index.ManualRefreshHandle
import dev.zacsweers.metro.idea.index.ManualRefreshOutcome
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.index.sharedDeclarationFingerprint
import dev.zacsweers.metro.idea.index.sourceAssistedFactoryUseSites
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingRejection
import dev.zacsweers.metro.idea.model.ConsumerResolution
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.tracing.IdeTraceOutput
import dev.zacsweers.metro.idea.tracing.IdeTraceRecorder
import dev.zacsweers.metro.idea.tracing.IdeTraceState
import dev.zacsweers.metro.idea.tracing.MetroIdeTracingService
import dev.zacsweers.metro.idea.tracing.RecordingIdeTraceSink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPsiFactory

class MetroResolutionServiceTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroResolutionService>().resetGraphBrowserActivation()
  }

  fun testRepeatedGlobalChangesAdvanceForceRevisionBeforeFirstSnapshot() {
    val file =
      myFixture.addFileToProject(
        "test/SharedConstant.kt",
        "package test\n\nconst val VALUE = 1",
      ) as KtFile
    withUnpublishedResolutionService { service ->
      var revision = service.awaitSourceInvalidationRevision()
      for (value in listOf(2, 3)) {
        WriteCommandAction.runWriteCommandAction(project) {
          val property = file.declarations.single() as KtProperty
          checkNotNull(property.initializer)
            .replace(KtPsiFactory(project).createExpression("$value"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val nextRevision = service.awaitSourceInvalidationRevision()
        assertTrue(
          "Each constant edit must invalidate previously forced shards",
          nextRevision > revision,
        )
        assertEquals(nextRevision, service.awaitSourceInvalidationRevision())
        assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
        revision = nextRevision
      }
    }
  }

  fun testRepeatedStructuralChangesAdvanceForceRevisionBeforeFirstSnapshot() {
    val file =
      myFixture.addFileToProject(
        "test/BeforeRename.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class RenamedFileBinding",
      ) as KtFile
    withUnpublishedResolutionService { service ->
      var revision = service.awaitSourceInvalidationRevision()
      for (name in listOf("FirstRename.kt", "SecondRename.kt")) {
        WriteCommandAction.runWriteCommandAction(project) { file.setName(name) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val nextRevision = service.awaitSourceInvalidationRevision()
        assertTrue(
          "Each structural edit must invalidate previously forced shards",
          nextRevision > revision,
        )
        assertEquals(nextRevision, service.awaitSourceInvalidationRevision())
        assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
        revision = nextRevision
      }
    }
  }

  fun testRepeatedClassificationFailuresAdvanceForceRevisionBeforeFirstSnapshot() {
    val file =
      myFixture.addFileToProject("test/ClassificationFailure.kt", "package test\n\nclass Before")
        as KtFile
    withUnpublishedResolutionService { service ->
      var revision = service.awaitSourceInvalidationRevision()
      for (name in listOf("FirstChange", "SecondChange")) {
        service.setPsiClassificationObserver {
          service.setPsiClassificationObserver(null)
          error("Test classification failure")
        }
        WriteCommandAction.runWriteCommandAction(project) {
          (file.declarations.single() as KtNamedDeclaration).setName(name)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val nextRevision = service.awaitSourceInvalidationRevision()
        assertTrue(
          "Each classification fallback must invalidate previously forced shards",
          nextRevision > revision,
        )
        assertEquals(nextRevision, service.awaitSourceInvalidationRevision())
        assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
        revision = nextRevision
      }
    }
  }

  /** Keeps accepted changes pending so revision checks cover edits before the first publication. */
  private fun withUnpublishedResolutionService(block: (MetroResolutionService) -> Unit) {
    val settings = MetroSettings.getInstance(project).state
    val automaticallyRefresh = settings.automaticallyRefreshGraphData
    settings.automaticallyRefreshGraphData = true
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    try {
      block(service)
    } finally {
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      settings.automaticallyRefreshGraphData = automaticallyRefresh
    }
  }

  /** Reads the revision after all PSI events from the preceding write have been classified. */
  private fun MetroResolutionService.awaitSourceInvalidationRevision(): Long {
    return runBlocking { withTimeout(30_000) { pendingSourceInvalidationRevision() } }
  }

  fun testQueuedPresentationRequestsUseLatestBindingsAndReusePublishedBundles() {
    fun presentationFile(name: String): KtFile {
      return myFixture.addFileToProject(
        "test/$name.kt",
        """
        package test

        import dev.zacsweers.metro.Inject

        @Inject class $name
        """
          .trimIndent(),
      ) as KtFile
    }

    val files = listOf("PresentationA", "PresentationB", "PresentationC").map(::presentationFile)
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val paused = CompletableFuture<Unit>()
    val release = CountDownLatch(1)

    try {
      val initial = service.awaitIndex(module)
      // Hold ordinary execution while presentation requests and a new source file queue.
      executor.submit {
        paused.complete(Unit)
        release.await()
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)
      repeat(5) {
        for (file in files) assertNull(service.presentationBundle(file.declarations.single()))
      }

      val added = presentationFile("PresentationAdded")
      assertTrue(ApplicationManager.getApplication().isDispatchThread)
      assertSame(BindingIndex.EMPTY, service.currentIndex(files.first()))
      assertSame(BindingIndex.EMPTY, service.currentIndex(added))
      assertNull(service.presentationBundle(added.declarations.single()))

      release.countDown()
      val refreshed = service.awaitIndex(added)
      assertNotSame(initial, refreshed)
      assertTrue(refreshed.bindings.any { it.typeKey.renderedType == "test.PresentationAdded" })
      for (file in files + added) {
        val bundle = file.awaitMetroPresentation(service)
        val declaration = file.declarations.single()
        repeat(3) { assertSame(bundle, service.presentationBundle(declaration)) }
      }
    } finally {
      release.countDown()
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testCachedPresentationMissNotifiesAfterNoOpClassificationAndExplicitUpgrade() {
    val file = configure()
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val paused = CompletableFuture<Unit>()
    val release = CountDownLatch(1)

    try {
      val initial = service.awaitIndex(file)
      service.activateGraphBrowser()
      UIUtil.dispatchAllInvocationEvents()
      val notified = CompletableFuture<Unit>()
      var notifications = 0
      service.addIndexListener(testRootDisposable) {
        notifications++
        notified.complete(Unit)
      }
      executor.submit {
        paused.complete(Unit)
        release.await()
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)

      // Reapplying unchanged settings makes readers wait for classification without a rebuild.
      service.settingsChanged()
      repeat(5) {
        assertSame(initial, service.indexForToolWindow(module))
        assertSame(BindingIndex.EMPTY, service.presentationIndex(file))
      }
      // The production EDT policy merges an explicit request with the presentation misses.
      assertSame(BindingIndex.EMPTY, service.currentIndex(file))

      release.countDown()
      PlatformTestUtil.waitForFuture(notified, 30_000)
      assertSame(initial, service.awaitIndex(file))
      repeat(5) { assertSame(initial, service.indexForToolWindow(module)) }
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      UIUtil.dispatchAllInvocationEvents()
      assertEquals(1, notifications)
    } finally {
      release.countDown()
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testCachedPresentationMissNotifiesAfterModuleStateWarmup() {
    val file = configure()
    val projectStateService = project.service<MetroIdeProjectService>()
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val paused = CompletableFuture<Unit>()
    val releaseCoordinator = CountDownLatch(1)
    val warmupStarted = CompletableFuture<Unit>()
    val releaseWarmup = CountDownLatch(1)

    try {
      val initial = service.awaitIndex(file)
      service.activateGraphBrowser()
      UIUtil.dispatchAllInvocationEvents()
      val notified = CompletableFuture<Unit>()
      var notifications = 0
      service.addIndexListener(testRootDisposable) {
        notifications++
        notified.complete(Unit)
      }
      executor.submit {
        paused.complete(Unit)
        releaseCoordinator.await()
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)
      projectStateService.clearCurrentState(module)
      projectStateService.setStateWarmupObserver {
        warmupStarted.complete(Unit)
        releaseWarmup.await()
      }

      service.settingsChanged()
      assertSame(initial, service.indexForToolWindow(module))
      assertTrue(service.hasGraphBrowserData)
      assertFalse(service.isCurrent(initial))
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
      PlatformTestUtil.waitForFuture(warmupStarted, 30_000)

      // Classification restores the cached generation before the warmup callback can retry.
      releaseCoordinator.countDown()
      val classified = CompletableFuture.supplyAsync {
        runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      }
      PlatformTestUtil.waitForFuture(classified, 30_000)
      assertTrue(service.isCurrent(initial))
      UIUtil.dispatchAllInvocationEvents()
      assertEquals(0, notifications)
      assertFalse(notified.isDone)
      releaseWarmup.countDown()

      PlatformTestUtil.waitForFuture(notified, 30_000)
      assertSame(initial, service.indexForToolWindow(module))
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      UIUtil.dispatchAllInvocationEvents()
      assertEquals(1, notifications)
    } finally {
      releaseCoordinator.countDown()
      releaseWarmup.countDown()
      projectStateService.setStateWarmupObserver(null)
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testInjectedRequestPolicyCanScheduleCurrentQueriesWithoutWaiting() {
    val file = configure()
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val selectedBackgroundCurrentMode = AtomicBoolean()
    val selectedPresentationMode = AtomicBoolean()
    val policy =
      object : IndexRequestPolicy {
        override fun currentRequestMode(isDispatchThread: Boolean): IndexRequestMode {
          selectedBackgroundCurrentMode.set(!isDispatchThread)
          return IndexRequestMode.BACKGROUND
        }

        override fun automaticPresentationRequestMode(): IndexRequestMode {
          selectedPresentationMode.set(true)
          return IndexRequestMode.AUTOMATIC_BACKGROUND
        }
      }
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        policy,
        AutomaticRefreshWindow(0, 0),
      )
    val paused = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) { notified.complete(Unit) }

    try {
      executor.submit {
        paused.complete(Unit)
        check(release.await(30, TimeUnit.SECONDS))
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)
      // A production background query would wait for the paused coordinator. The injected policy
      // returns its cache miss after queueing the same build, with no test-mode branch involved.
      val queried = CompletableFuture.supplyAsync {
        runBlocking {
          smartReadAction(project) {
            project.service<MetroIdeProjectService>().state(module)
            service.currentIndex(file)
          }
        }
      }
      assertSame(BindingIndex.EMPTY, PlatformTestUtil.waitForFuture(queried, 30_000))
      assertTrue(selectedBackgroundCurrentMode.get())
      assertSame(BindingIndex.EMPTY, service.presentationIndex(file))
      assertTrue(selectedPresentationMode.get())

      release.countDown()
      PlatformTestUtil.waitForFuture(notified, 30_000)
      assertFalse(service.awaitIndex(file).bindings.isEmpty())
    } finally {
      release.countDown()
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testTemporaryProjectClosurePreservesPendingPsiClassification() {
    val file = myFixture.configureMetroFile("@Inject class BeforeClosure")
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val interrupted = AtomicBoolean()
    try {
      val initial = service.awaitIndex(file)
      service.setPsiClassificationObserver {
        service.setPsiClassificationObserver(null)
        interrupted.set(true)
        // Capture the unavailable phase without closing the platform fixture itself. The next
        // classification attempt sees the available project and must apply the retained batch.
        service.checkPsiClassificationActive(projectDisposed = true)
      }
      WriteCommandAction.runWriteCommandAction(project) {
        val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
        document.setText(file.text.replace("BeforeClosure", "AfterClosure"))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
      val updated = service.awaitIndex(file)
      assertTrue(interrupted.get())
      assertNotSame(initial, updated)
      assertEquals(listOf("AfterClosure"), updated.bindings.map { it.implementationName })
    } finally {
      service.setPsiClassificationObserver(null)
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
    }
  }

  fun testQueuedIndexRequestsShareThePublishedIndex() {
    configure()
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val paused = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    executor.submit {
      paused.complete(Unit)
      release.await()
    }
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val scheduledRequests = CountDownLatch(6)
    val requestExecutor = Executors.newFixedThreadPool(6)

    fun requestIndex(): CompletableFuture<BindingIndex> {
      return CompletableFuture.supplyAsync(
        {
          runBlocking {
            retryCancelledIndexBuild {
              smartReadAction(project) {
                try {
                  service.currentIndex(module)
                } finally {
                  // A pending query releases its read action after submitting the build request.
                  scheduledRequests.countDown()
                }
              }
            }
          }
        },
        requestExecutor,
      )
    }

    try {
      PlatformTestUtil.waitForFuture(paused, 30_000)
      val requests = List(6) { requestIndex() }
      assertTrue(scheduledRequests.await(30, TimeUnit.SECONDS))
      assertTrue(requests.none { it.isDone })

      release.countDown()
      val indexes = requests.map { PlatformTestUtil.waitForFuture(it, 30_000) }
      val initial = indexes.first()
      assertNotSame(BindingIndex.EMPTY, initial)
      indexes.forEach { assertSame(initial, it) }
      assertSame(initial, service.awaitIndex(module))

      service.refreshGraphData()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      assertNotSame(initial, service.awaitIndex(module))
    } finally {
      release.countDown()
      requestExecutor.shutdownNow()
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testDoesNotBuildAnIndexWhenMetroIsNotConfigured() {
    project.clearMetroOptions()
    val file = configure()

    assertTrue(project.service<MetroResolutionService>().awaitIndex(file).bindings.isEmpty())
  }

  fun testColdIndexFindsFilesUsingOnlyAliasedMetroAnnotations() {
    myFixture.addFileToProject(
      "test/AliasedGraph.kt",
      """
      package test

      import dev.zacsweers.metro.DependencyGraph as MetroGraph
      import dev.zacsweers.metro.Inject as MetroInject

      @MetroInject class AliasedService

      @MetroGraph
      interface AliasedGraph {
        val service: AliasedService
      }
      """
        .trimIndent(),
    )

    val index = project.service<MetroResolutionService>().awaitIndex(module)

    assertEquals(listOf("AliasedGraph"), index.graphs.map { it.name })
    assertEquals(listOf("test.AliasedService"), index.bindings.map { it.typeKey.renderedType })
  }

  fun testUnrelatedAliasedAnnotationDoesNotActivateMetroIndexing() {
    myFixture.addFileToProject("other/Inject.kt", "package other\n\nannotation class Inject")
    myFixture.addFileToProject(
      "test/Unrelated.kt",
      """
      package test

      import other.Inject as MetroInject

      @MetroInject class Unrelated
      """
        .trimIndent(),
    )

    val index = project.service<MetroResolutionService>().awaitIndex(module)

    assertTrue(index.bindings.isEmpty())
    assertTrue(index.graphs.isEmpty())
  }

  fun testContributionProviderOptionInvalidatesSemanticIndexFingerprint() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject @ContributesBinding(AppScope::class)
        class ServiceImpl : Service
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    assertEquals(
      setOf("test.Service", "test.ServiceImpl"),
      initial.bindings.mapTo(mutableSetOf()) { it.typeKey.renderedType },
    )

    project.setMetroOptions("generate-contribution-providers" to "true")

    val generated = service.awaitIndex(file)
    assertNotSame(initial, generated)
    assertEquals(listOf("test.Service"), generated.bindings.map { it.typeKey.renderedType })
    assertTrue(generated.bindings.single() is KaBinding.Provided)
  }

  fun testLibraryRootChangesNotifyExistingIndexListeners() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }

    module.withMetroLibFixtureLibrary {
      // Root changes reconcile project inputs before a deferred callback notifies listeners.
      PlatformTestUtil.waitForFuture(notified, 30_000)

      assertTrue("Changing library roots should refresh an open Metro window", notifications > 0)
    }
  }

  fun testRootChangesNotifyListenersBeforeTheFirstMetroSnapshot() {
    project.clearMetroOptions()
    val service = project.service<MetroResolutionService>()
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }

    module.withMetroLibFixtureLibrary {
      project.setMetroOptions()
      PlatformTestUtil.waitForFuture(notified, 30_000)

      assertTrue("An open window should notice Metro becoming available", notifications > 0)
    }
  }

  fun testCompilerSettingsChangesNotifyExistingIndexListenersWithoutRootChanges() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }
    val initialRoots = ProjectRootModificationTracker.getInstance(project).modificationCount

    project.setMetroOptions("generate-contribution-providers" to "true")
    PlatformTestUtil.waitForFuture(notified, 30_000)
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

    assertEquals(
      initialRoots,
      ProjectRootModificationTracker.getInstance(project).modificationCount,
    )
    assertTrue("Compiler options should refresh an open Metro window", notifications > 0)
  }

  fun testCompilerSettingsEnableMetroBeforeTheFirstSnapshotWithoutRootChanges() {
    project.clearMetroOptions()
    val service = project.service<MetroResolutionService>()
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }
    val initialRoots = ProjectRootModificationTracker.getInstance(project).modificationCount

    project.setMetroOptions()
    PlatformTestUtil.waitForFuture(notified, 30_000)

    assertEquals(
      initialRoots,
      ProjectRootModificationTracker.getInstance(project).modificationCount,
    )
    assertTrue("An open window should notice Metro becoming available", notifications > 0)
  }

  fun testCompilerSettingsDisableAndReenableMetroWithoutRootChanges() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    assertFalse(service.awaitIndex(file).bindings.isEmpty())
    service.activateGraphBrowser()
    assertFalse(service.indexForToolWindow(module).graphs.isEmpty())
    assertTrue(service.hasGraphBrowserData)
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    UIUtil.dispatchAllInvocationEvents()
    var notifications = 0
    var notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }
    val initialRoots = ProjectRootModificationTracker.getInstance(project).modificationCount

    project.setMetroOptions("enabled" to "false")
    PlatformTestUtil.waitForFuture(notified, 30_000)
    assertTrue(service.awaitIndex(file).bindings.isEmpty())
    assertSame(BindingIndex.EMPTY, service.indexForToolWindow(module))
    assertFalse(service.hasGraphBrowserData)
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    UIUtil.dispatchAllInvocationEvents()
    val disabledNotifications = notifications
    assertTrue("Disabling Metro should refresh an open window", disabledNotifications > 0)

    notified = CompletableFuture()
    project.setMetroOptions()
    PlatformTestUtil.waitForFuture(notified, 30_000)
    assertEquals(
      initialRoots,
      ProjectRootModificationTracker.getInstance(project).modificationCount,
    )
    assertTrue(
      "Reenabling Metro should refresh an open window",
      notifications > disabledNotifications,
    )
    assertFalse(service.awaitIndex(file).bindings.isEmpty())
    assertFalse(service.indexForToolWindow(module).graphs.isEmpty())
    assertTrue(service.hasGraphBrowserData)
  }

  fun testRemovingMetroCompilerSettingsNotifiesExistingIndexListenersWithoutRootChanges() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }
    val initialRoots = ProjectRootModificationTracker.getInstance(project).modificationCount

    project.clearMetroOptions()
    PlatformTestUtil.waitForFuture(notified, 30_000)

    assertEquals(
      initialRoots,
      ProjectRootModificationTracker.getInstance(project).modificationCount,
    )
    assertTrue("Removing Metro options should refresh an open window", notifications > 0)
    assertTrue(service.awaitIndex(file).bindings.isEmpty())
  }

  fun testBatchedCompilerSettingsChangesKeepTheLatestIndexAndNotifyOnce() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    UIUtil.dispatchAllInvocationEvents()
    var notifications = 0
    val notified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      notifications++
      notified.complete(Unit)
    }

    project.setMetroOptions("generate-contribution-providers" to "true")
    project.setMetroOptions(
      "generate-contribution-providers" to "true",
      "reports-destination" to "/tmp/metro-batched",
    )
    project.setMetroOptions(
      "generate-contribution-providers" to "true",
      "enable-suspend-providers" to "true",
    )

    PlatformTestUtil.waitForFuture(notified, 30_000)
    assertEquals(1, notifications)

    // The settings notification arrives before the explicitly requested index is published.
    val latest = service.awaitIndex(file)
    assertNotSame(initial, latest)
    assertSame(latest, service.awaitIndex(file))
  }

  fun testBatchedOutputOnlyCompilerSettingsRestoreTheExistingIndex() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    UIUtil.dispatchAllInvocationEvents()

    project.setMetroOptions("reports-destination" to "/tmp/metro-first")
    project.setMetroOptions("reports-destination" to "/tmp/metro-second")
    project.setMetroOptions(
      "reports-destination" to "/tmp/metro-third",
      "trace-destination" to "/tmp/metro-traces",
    )
    UIUtil.dispatchAllInvocationEvents()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    UIUtil.dispatchAllInvocationEvents()
    assertFalse(service.isGraphDataRefreshRequired)
    assertSame(initial, service.awaitIndex(file))
  }

  fun testPlatformCancellationRetriesTheRequestedIndexBuild() {
    var attempts = 0

    val result = runBlocking {
      retryCancelledIndexBuild {
        attempts++
        if (attempts == 1) throw ProcessCanceledException()
        "ready"
      }
    }

    assertEquals("ready", result)
    assertEquals(2, attempts)
  }

  fun testToolWindowIndexWaitsForActivationThenBuildsInBackgroundAndReportsProgress() {
    val projectStateService = project.service<MetroIdeProjectService>()
    configure()
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val paused = CompletableFuture<Unit>()
    val releaseCoordinator = CountDownLatch(1)
    projectStateService.clearCurrentState(module)
    val progress = mutableListOf<IndexBuildProgress?>()
    val completed = CompletableFuture<Unit>()
    val warmupStarted = CompletableFuture<Pair<Boolean, Boolean>>()
    var started = false

    val progressUpdates =
      service.indexBuildProgress.collectInTest { update ->
        progress += update
        if (update != null) {
          started = true
        } else if (started) {
          completed.complete(Unit)
        }
      }
    projectStateService.setStateWarmupObserver {
      val application = ApplicationManager.getApplication()
      warmupStarted.complete(application.isDispatchThread to application.isReadAccessAllowed)
    }

    try {
      assertNull(projectStateService.currentStateOrNull(module))
      assertFalse(service.isGraphBrowserActivated)
      assertFalse(service.hasGraphBrowserData)
      assertSame(BindingIndex.EMPTY, service.indexForToolWindow(module))
      PlatformTestUtil.waitForFuture(warmupStarted, 30_000)
      val warmupContext = warmupStarted.join()
      assertFalse("Compiler settings must not load on the EDT", warmupContext.first)
      assertTrue("Compiler settings warmup needs read access", warmupContext.second)

      assertTrue(progress.none { it != null })
      object : WaitFor(30_000) {
          override fun condition(): Boolean = projectStateService.currentStateOrNull(module) != null
        }
        .assertCompleted("Compiler settings should finish warming in the background")

      // Keep the first build queued so its publication cannot race the cold-browser assertion.
      executor.submit {
        paused.complete(Unit)
        releaseCoordinator.await()
      }
      PlatformTestUtil.waitForFuture(paused, 30_000)
      service.activateGraphBrowser()
      assertTrue(service.isGraphBrowserActivated)
      assertSame(BindingIndex.EMPTY, service.indexForToolWindow(module))
      assertFalse(service.hasGraphBrowserData)
      releaseCoordinator.countDown()
      PlatformTestUtil.waitForFuture(completed, 30_000)
      progressUpdates.close()

      assertNotSame(BindingIndex.EMPTY, service.indexForToolWindow(module))
      assertTrue(service.hasGraphBrowserData)
      val phases = progress.mapNotNull { it?.phase }.toSet()
      assertTrue(IndexBuildPhase.DISCOVERING_SOURCE_FILES in phases)
      assertTrue(IndexBuildPhase.ANALYZING_DECLARATIONS in phases)
      assertTrue(IndexBuildPhase.COMBINING_DECLARATIONS in phases)
      assertTrue(IndexBuildPhase.BUILDING_GRAPH_INDEX in phases)
      assertNull(progress.last())
    } finally {
      releaseCoordinator.countDown()
      progressUpdates.close()
      projectStateService.setStateWarmupObserver(null)
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      dispatcher.close()
    }
  }

  fun testToolWindowUsesAnIndexAlreadyBuiltByEditorFeatures() {
    configure()
    val service = project.service<MetroResolutionService>()
    val warmIndex = service.awaitIndex(module)

    assertFalse(service.isGraphBrowserActivated)
    assertSame(warmIndex, service.indexForToolWindow(module))
    assertTrue(service.isGraphBrowserActivated)
  }

  fun testManualModeDefersClassificationUntilRefresh() {
    val file = configure()
    withTimedResolutionService(AutomaticRefreshWindow(0, 0)) { service ->
      val initial = service.awaitIndex(file)
      service.activateGraphBrowser()
      MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
      service.settingsChanged()
      awaitCoordinator(service)
      val classifications = AtomicInteger()
      service.setPsiClassificationObserver { classifications.incrementAndGet() }

      appendBinding(file, "ManualAddition")
      awaitCoordinator(service)
      repeat(3) {
        assertSame(initial, service.presentationIndex(file))
        assertSame(initial, service.indexForToolWindow(module))
      }
      awaitCoordinator(service)
      assertEquals(0, classifications.get())
      assertNull(service.indexBuildProgress.value)
      assertTrue(service.isGraphDataRefreshRequired)
      assertFalse(service.isAutomaticGraphDataRefreshPending)

      awaitRefreshedBinding(service, file, "ManualAddition") { service.refreshGraphData() }
      assertEquals(1, classifications.get())
      assertFalse(service.isGraphDataRefreshRequired)
    }
  }

  fun testAutomaticEditsWaitForIdleAndRefreshBypassesTheInterval() {
    val now = AtomicLong()
    val window = AutomaticRefreshWindow(nowMillis = now::get)
    val file = configure()
    withTimedResolutionService(window) { service ->
      service.awaitIndex(file)
      service.activateGraphBrowser()
      val classifications = AtomicInteger()
      service.setPsiClassificationObserver { classifications.incrementAndGet() }

      appendBinding(file, "FirstBatchedAddition")
      awaitCoordinator(service)
      assertTrue(service.isAutomaticGraphDataRefreshPending)
      assertEquals(0, classifications.get())
      now.set(1_000)
      appendBinding(file, "SecondBatchedAddition")
      repeat(3) { service.presentationIndex(file) }
      now.set(2_000)
      service.wakeAutomaticRefreshForTest()
      awaitCoordinator(service)
      assertEquals(0, classifications.get())
      assertNull(service.indexBuildProgress.value)

      now.set(3_000)
      awaitRefreshedBinding(service, file, "SecondBatchedAddition") {
        service.wakeAutomaticRefreshForTest()
      }
      assertEquals(1, classifications.get())
      now.set(4_000)
      appendBinding(file, "ThirdBatchedAddition")
      now.set(6_000)
      service.wakeAutomaticRefreshForTest()
      awaitCoordinator(service)
      assertEquals(1, classifications.get())
      assertTrue(service.isAutomaticGraphDataRefreshPending)

      awaitRefreshedBinding(service, file, "ThirdBatchedAddition") { service.refreshGraphData() }
      assertEquals(2, classifications.get())
      assertFalse(service.isGraphDataRefreshRequired)
      now.set(7_000)
      appendBinding(file, "ExplicitLookupAddition")
      val current = service.awaitIndex(file)
      assertTrue(current.bindings.any { it.typeKey.renderedType == "test.ExplicitLookupAddition" })
      assertEquals(3, classifications.get())
    }
  }

  fun testAutomaticWindowExpiryWhileSchedulingStillWakesTheCoordinator() {
    // The gate sees one millisecond remaining; scheduling sees the deadline has just passed.
    val now = AtomicLong()
    val window =
      AutomaticRefreshWindow(idleMillis = 2, intervalMillis = 0, nowMillis = now::incrementAndGet)
    val file = configure()
    withTimedResolutionService(window) { service ->
      service.awaitIndex(file)
      awaitRefreshedBinding(service, file, "DeadlineAddition") {
        appendBinding(file, "DeadlineAddition")
      }
      assertFalse(service.isGraphDataRefreshRequired)
    }
  }

  fun testSwitchingToManualCancelsAutomaticClassificationAndRefreshStillWorks() {
    val file = configure()
    withTimedResolutionService(AutomaticRefreshWindow(0, 0)) { service ->
      val initial = service.awaitIndex(file)
      service.activateGraphBrowser()
      val reachedClassification = CompletableFuture<Unit>()
      val release = CountDownLatch(1)
      service.setPsiClassificationObserver {
        reachedClassification.complete(Unit)
        check(release.await(30, TimeUnit.SECONDS))
      }
      try {
        appendBinding(file, "AfterCancellation")
        PlatformTestUtil.waitForFuture(reachedClassification, 30_000)
        MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
        service.settingsChanged()
      } finally {
        service.setPsiClassificationObserver(null)
        release.countDown()
      }
      awaitCoordinator(service)
      assertSame(initial, service.presentationIndex(file))
      assertNull(service.indexBuildProgress.value)
      assertTrue(service.isGraphDataRefreshRequired)
      awaitRefreshedBinding(service, file, "AfterCancellation") { service.refreshGraphData() }
      assertFalse(service.isGraphDataRefreshRequired)
    }
  }

  fun testUnrelatedXmlPsiChangeDoesNotRestartColdManualLoad() {
    val settingsFile =
      myFixture.addFileToProject("config/options.xml", "<settings value=\"before\"/>") as XmlFile
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val tag = checkNotNull(settingsFile.document?.rootTag)

    withPausedColdManualRefresh { service, attempts, release ->
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
      WriteCommandAction.runWriteCommandAction(project) { tag.setAttribute("value", "after") }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      assertEquals("after", tag.getAttributeValue("value"))

      release.countDown()
      awaitCoordinator(service)
      assertEquals(1, attempts.get())
      assertEquals(listOf("AppGraph"), service.cachedIndex(file).graphs.map { it.name })
      assertFalse(service.isExplicitGraphRefreshPending)
    }
  }

  fun testJavaDependencyChangeRestartsColdManualLoad() {
    module.addKotlinStdlibLibrary()
    val base =
      myFixture.addFileToProject(
        "test/JavaBase.java",
        """
        package test;

        import dev.zacsweers.metro.HasMemberInjections;
        import dev.zacsweers.metro.Inject;

        @HasMemberInjections
        public class JavaBase {
          @Inject public void install(OldService service) {}
        }
        """
          .trimIndent(),
      ) as PsiJavaFile
    val file =
      myFixture.configureMetroFile(
        """
        interface OldService
        interface NewService

        @Inject class Screen : JavaBase()

        @DependencyGraph interface AppGraph {
          val screen: Screen
        }
        """
      )

    withPausedColdManualRefresh { service, attempts, release ->
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
      WriteCommandAction.runWriteCommandAction(project) {
        val parameter = base.classes.single().methods.single().parameterList.parameters.single()
        val updatedType =
          JavaPsiFacade.getElementFactory(project)
            .createTypeElementFromText("NewService", parameter)
        checkNotNull(parameter.typeElement).replace(updatedType)
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      release.countDown()
      awaitCoordinator(service)
      assertEquals(2, attempts.get())
      val binding =
        service.cachedIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
      val dependencyType = binding.dependencies.single().typeKey.type
      assertFalse(dependencyType.renderedType, dependencyType.isError)
      assertEquals("test.NewService!", dependencyType.renderedType)
    }
  }

  fun testPassiveQueryForDiscoveredFileDoesNotRestartColdManualLoad() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    withPausedColdManualRefresh { service, attempts, release ->
      repeat(3) { assertSame(BindingIndex.EMPTY, service.presentationIndex(file)) }

      release.countDown()
      awaitCoordinator(service)
      assertEquals(1, attempts.get())
      assertEquals(listOf("AppGraph"), service.cachedIndex(file).graphs.map { it.name })
      assertFalse(service.isExplicitGraphRefreshPending)
    }
  }

  fun testManualRefreshHandleSurvivesRetryAndCanceledObserver() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    var request: ManualRefreshHandle? = null
    withPausedColdManualRefresh(onRequested = { request = it }) { service, attempts, release ->
      val handle = checkNotNull(request)
      assertFalse(handle.completion.isCompleted)
      assertNull(service.refreshGraphData())
      val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val observing = observerScope.launch { handle.completion.await() }
      runBlocking { observing.cancelAndJoin() }
      observerScope.cancel()
      assertFalse(handle.completion.isCancelled)
      myFixture.addFileToProject(
        "test/AddedDuringTracedRefresh.kt",
        "package test\n@dev.zacsweers.metro.Inject class AddedDuringTracedRefresh",
      )
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      assertFalse(handle.completion.isCompleted)
      release.countDown()
      awaitCoordinator(service)
      assertEquals(2, attempts.get())
      assertEquals(ManualRefreshOutcome.PUBLISHED, runBlocking { handle.completion.await() })
      assertFalse(service.isExplicitGraphRefreshPending)
      assertEquals(
        "test.AddedDuringTracedRefresh",
        service.cachedIndex(file).bindings.single().typeKey.renderedType,
      )
    }
  }

  fun testNewFileAfterDiscoveryRestartsColdManualLoad() = withResolutionTrace { recorder, sink ->
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    withPausedColdManualRefresh { service, attempts, release ->
      val added =
        myFixture.addFileToProject(
          "test/AddedAfterDiscovery.kt",
          "package test\n\n@dev.zacsweers.metro.Inject class AddedAfterDiscovery",
        ) as KtFile
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      assertSame(BindingIndex.EMPTY, service.presentationIndex(added))

      release.countDown()
      awaitCoordinator(service)
      assertEquals(2, attempts.get())
      val index = service.cachedIndex(file)
      assertEquals(listOf("AppGraph"), index.graphs.map { it.name })
      assertEquals("test.AddedAfterDiscovery", index.bindings.single().typeKey.renderedType)
      assertSame(index, service.cachedIndex(added))
      assertFalse(service.isExplicitGraphRefreshPending)
      added.awaitMetroPresentation(service)
      awaitCoordinator(service)
    }
    assertEquals(IdeTraceState.RECORDING, recorder.state.value)
    recorder.stop()
    runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } } }
    val candidateEvents = sink.results("index.candidate")
    val manualRequestIds =
      candidateEvents
        .filter { it.metadata["intent"] == "MANUAL_REFRESH" }
        .map { checkNotNull(it.metadata["manualRequest"]) }
        .toSet()
    assertEquals(1, manualRequestIds.size)
    val manualRequest = manualRequestIds.single()
    // The project capture also includes independently requested background generations.
    // Admission IDs order retries across packets flushed from different threads.
    val candidates =
      candidateEvents
        .filter {
          it.metadata["intent"] == "MANUAL_REFRESH" && it.metadata["manualRequest"] == manualRequest
        }
        .sortedBy { checkNotNull(it.metadata["operation_id"]).toLong() }
    assertEquals(
      candidateEvents.map { it.metadata }.toString(),
      listOf("superseded", "published"),
      candidates.map { it.metadata["outcome"] },
    )
    val discardedAttempt = candidates.first().metadata["operation_id"]
    assertTrue(
      sink.results("index.captureInputs").any {
        it.metadata["parent_operation_id"] == discardedAttempt &&
          it.metadata["outcome"] == "completed"
      }
    )
    val publishedGeneration = checkNotNull(candidates.last().metadata["generation"])
    val presentation =
      sink
        .results("presentation.build")
        .single { it.metadata["generation"] == publishedGeneration }
        .metadata
    assertEquals("completed", presentation["outcome"])
    assertTrue(
      sink.events.any {
        it.name == "presentation.publication" &&
          it.metadata["attempt"] == presentation["attempt"] &&
          it.metadata["disposition"] == "published"
      }
    )
  }

  /** Captures real service work while keeping the fixture's normal recorder available afterward. */
  private fun withResolutionTrace(block: (IdeTraceRecorder, RecordingIdeTraceSink) -> Unit) {
    val settings = MetroSettings.getInstance(project).state
    val previousDebugging = settings.enableDebuggingOptions
    settings.enableDebuggingOptions = true
    val job = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val finished = CompletableFuture<Throwable?>()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(job + Dispatchers.Default),
        createOutput = { IdeTraceOutput(TraceDriver(sink)) },
        onFinished = { _, failure, _, _ -> finished.complete(failure) },
      )
    val tracingService = project.service<MetroIdeTracingService>()
    val previousRecorder = tracingService.setRecorderForTest(recorder)
    try {
      tracingService.startCapture()
      runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.RECORDING } } }
      block(recorder, sink)
    } finally {
      val traceFailure: Throwable?
      try {
        recorder.stop()
        traceFailure = PlatformTestUtil.waitForFuture(finished, 30_000)
        runBlocking { withTimeout(30_000) { recorder.state.first { it == IdeTraceState.IDLE } } }
      } finally {
        try {
          runBlocking { job.cancelAndJoin() }
        } finally {
          try {
            tracingService.setRecorderForTest(previousRecorder)
          } finally {
            settings.enableDebuggingOptions = previousDebugging
          }
        }
      }
      if (traceFailure != null) throw AssertionError("Trace recording failed", traceFailure)
    }
  }

  fun testRepeatedRefreshRequestsShareTheRunningManualLoad() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    withPausedColdManualRefresh { service, attempts, release ->
      repeat(2) { service.refreshGraphData() }
      assertTrue(service.isExplicitGraphRefreshPending)
      release.countDown()
      awaitCoordinator(service)
      assertEquals(1, attempts.get())
      assertEquals(listOf("AppGraph"), service.cachedIndex(file).graphs.map { it.name })
      assertFalse(service.isExplicitGraphRefreshPending)

      service.refreshGraphData()
      awaitCoordinator(service)
      assertEquals(2, attempts.get())
      assertFalse(service.isExplicitGraphRefreshPending)
    }
  }

  fun testFailedManualLoadAllowsAnotherRefresh() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    withTimedResolutionService(AutomaticRefreshWindow(0, 0)) { service ->
      MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
      service.settingsChanged()
      awaitCoordinator(service)
      val attempts = AtomicInteger()
      service.setResolutionCandidatePreparedObserver {
        if (attempts.incrementAndGet() == 1) error("Test index build failure")
      }

      service.refreshGraphData()
      awaitCoordinator(service)
      assertFalse(service.isExplicitGraphRefreshPending)
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))

      service.refreshGraphData()
      awaitCoordinator(service)
      assertEquals(2, attempts.get())
      assertEquals(listOf("AppGraph"), service.cachedIndex(file).graphs.map { it.name })
      assertFalse(service.isExplicitGraphRefreshPending)
    }
  }

  fun testCancelingServiceScopeReleasesTheManualRefreshRequest() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val settings = MetroSettings.getInstance(project).state
    val previousMode = settings.automaticallyRefreshGraphData
    settings.automaticallyRefreshGraphData = false
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        AutomaticRefreshWindow(0, 0),
      )
    val prepared = CompletableFuture<Unit>()
    val release = CountDownLatch(1)
    service.setResolutionCandidatePreparedObserver {
      prepared.complete(Unit)
      check(release.await(30, TimeUnit.SECONDS))
    }
    try {
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(prepared, 30_000)
      assertTrue(service.isExplicitGraphRefreshPending)
      serviceScope.cancel()
      release.countDown()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      assertFalse(service.isExplicitGraphRefreshPending)
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
      service.refreshGraphData()
      assertFalse(service.isExplicitGraphRefreshPending)
    } finally {
      release.countDown()
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      settings.automaticallyRefreshGraphData = previousMode
    }
  }

  /** Pauses after capture so real PSI writes can race publication outside its read action. */
  private fun withPausedColdManualRefresh(
    onRequested: (ManualRefreshHandle) -> Unit = {},
    block: (MetroResolutionService, AtomicInteger, CountDownLatch) -> Unit,
  ) {
    withTimedResolutionService(AutomaticRefreshWindow(0, 0)) { service ->
      MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
      service.settingsChanged()
      awaitCoordinator(service)
      val prepared = CompletableFuture<Unit>()
      val attempts = AtomicInteger()
      val release = CountDownLatch(1)
      service.setResolutionCandidatePreparedObserver {
        assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
        if (attempts.incrementAndGet() == 1) {
          prepared.complete(Unit)
          check(release.await(30, TimeUnit.SECONDS))
        }
      }
      try {
        onRequested(checkNotNull(service.refreshGraphData()))
        PlatformTestUtil.waitForFuture(prepared, 30_000)
        assertTrue(service.isExplicitGraphRefreshPending)
        block(service, attempts, release)
      } finally {
        release.countDown()
        service.setResolutionCandidatePreparedObserver(null)
      }
    }
  }

  /** Isolated services keep timing assertions independent of retained fixture generations. */
  private fun withTimedResolutionService(
    window: AutomaticRefreshWindow,
    block: (MetroResolutionService) -> Unit,
  ) {
    val settings = MetroSettings.getInstance(project).state
    val previousMode = settings.automaticallyRefreshGraphData
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val service =
      MetroResolutionService.createForTest(
        project,
        serviceScope,
        IndexRequestPolicy.Production,
        window,
      )
    try {
      block(service)
    } finally {
      Disposer.dispose(service)
      serviceScope.cancel()
      serviceScope.coroutineContext.job.awaitTestCompletion()
      settings.automaticallyRefreshGraphData = previousMode
    }
  }

  private fun awaitCoordinator(service: MetroResolutionService) {
    val completion = CompletableFuture.runAsync {
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    }
    PlatformTestUtil.waitForFuture(completion, 30_000)
  }

  private fun appendBinding(file: KtFile, name: String) {
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(document.textLength, "\n\n@Inject class $name")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  /** Observes publication through cache-only reads, so waiting cannot bypass the refresh window. */
  private fun awaitRefreshedBinding(
    service: MetroResolutionService,
    file: KtFile,
    name: String,
    request: () -> Unit,
  ) {
    val finished = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) {
      if (service.cachedIndex(file).bindings.any { it.typeKey.renderedType == "test.$name" }) {
        finished.complete(Unit)
      }
    }
    request()
    PlatformTestUtil.waitForFuture(finished, 30_000)
  }

  fun testDisabledAutomaticRefreshKeepsPresentationSnapshotUntilManualRefresh() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()

    try {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n\n@Inject class AddedAfterRefresh")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val stale = service.presentationIndex(file)
      assertSame(initial, stale)
      assertFalse(stale.bindings.any { it.typeKey.renderedType == "test.AddedAfterRefresh" })
      assertTrue(service.isManualGraphDataRefreshRequired)
      UIUtil.dispatchAllInvocationEvents()

      val refreshFinished = CompletableFuture<Unit>()
      var refreshNotifications = 0
      service.addIndexListener(testRootDisposable) {
        val refreshed = service.cachedIndex(file)
        if (
          !service.isManualGraphDataRefreshRequired &&
            refreshed.bindings.any { it.typeKey.renderedType == "test.AddedAfterRefresh" }
        ) {
          refreshNotifications++
          refreshFinished.complete(Unit)
        }
      }
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(refreshFinished, 30_000)

      val refreshed = service.presentationIndex(file)
      assertNotSame(initial, refreshed)
      assertTrue(refreshed.bindings.any { it.typeKey.renderedType == "test.AddedAfterRefresh" })
      assertFalse(service.isManualGraphDataRefreshRequired)
      assertEquals(1, refreshNotifications)
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testExplicitGraphLookupWaitsForNewGraphInManualRefreshMode() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    UIUtil.dispatchAllInvocationEvents()

    try {
      val staleNotification = CompletableFuture<Unit>()
      var notifications = 0
      service.addIndexListener(testRootDisposable) {
        notifications++
        staleNotification.complete(Unit)
      }
      val added =
        myFixture.addFileToProject(
          "test/AddedGraph.kt",
          "package test\n\n@dev.zacsweers.metro.DependencyGraph interface AddedGraph",
        ) as KtFile
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      PlatformTestUtil.waitForFuture(staleNotification, 30_000)
      assertTrue(service.isManualGraphDataRefreshRequired)
      assertSame(initial, service.indexForToolWindow(module))

      // A first validation action must finish even after the browser consumed its stale notice.
      val found = CompletableFuture<KaGraphDeclaration?>()
      val lookup =
        service.findGraphAsync(ClassId.topLevel(FqName("test.AddedGraph")), added.virtualFile) {
          assertTrue(ApplicationManager.getApplication().isDispatchThread)
          found.complete(it)
        }
      val graph = PlatformTestUtil.waitForFuture(found, 30_000)
      lookup.awaitTestCompletion()
      UIUtil.dispatchAllInvocationEvents()

      assertEquals("AddedGraph", checkNotNull(graph).name)
      assertEquals(added.virtualFile, graph.pointer.virtualFile)
      assertEquals(1, notifications)
      assertTrue(service.isManualGraphDataRefreshRequired)
      assertSame(initial, service.indexForToolWindow(module))
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testManualRefreshUpdatesTypeAliases() {
    val aliases =
      myFixture.addFileToProject("test/Aliases.kt", "package test\n\ntypealias Alias = String")
    myFixture.addFileToProject(
      "test/Unrelated.kt",
      "package test\n\n@dev.zacsweers.metro.Inject class Unrelated",
    )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides fun provideAlias(): Alias = error("unused")
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    assertTrue(
      initial.bindings.any { it.typeKey.type.classId?.asFqNameString() == "kotlin.String" }
    )
    val initialUnrelated = initial.bindings.single { it.typeKey.renderedType == "test.Unrelated" }
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

    try {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(aliases))
      WriteCommandAction.runWriteCommandAction(project) {
        val start = document.text.indexOf("String")
        document.replaceString(start, start + "String".length, "Int")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      service.refreshGraphData()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val refreshed = service.presentationIndex(file)
      assertTrue(
        refreshed.bindings.any { it.typeKey.type.classId?.asFqNameString() == "kotlin.Int" }
      )
      assertNotSame(
        initialUnrelated,
        refreshed.bindings.single { it.typeKey.renderedType == "test.Unrelated" },
      )
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testManualRefreshIncludesNewFiles() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

    try {
      myFixture.addFileToProject(
        "test/AddedBeforeRefresh.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class AddedBeforeRefresh",
      )
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      service.refreshGraphData()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      assertTrue(
        service.presentationIndex(file).bindings.any {
          it.typeKey.renderedType == "test.AddedBeforeRefresh"
        }
      )
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testDisabledAutomaticRefreshFreezesColdContributionResolutionUntilManualRefresh() {
    val contributionFile =
      myFixture.addFileToProject(
        "test/Contribution.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.Inject

        interface Service

        @ContributesBinding(AppScope::class)
        @Inject
        class ServiceImpl : Service
        """
          .trimIndent(),
      ) as KtFile
    val graphFile =
      myFixture.addFileToProject(
        "test/ContributionGraph.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph

        @DependencyGraph(AppScope::class)
        interface ContributionGraph {
          val service: Service
        }
        """
          .trimIndent(),
      ) as KtFile
    val service = project.service<MetroResolutionService>()
    val initialPresentation = service.awaitIndex(graphFile)
    assertSame(initialPresentation, service.presentationIndex(graphFile))
    assertTrue(initialPresentation.bindings.any { it.implementationName == "ServiceImpl" })

    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()

    try {
      val contribution = contributionFile.declarationsIncludingNested().klass("ServiceImpl")
      WriteCommandAction.runWriteCommandAction(project) { contribution.delete() }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val presentation = service.presentationIndex(graphFile)
      val current = service.awaitIndex(graphFile)
      assertSame(initialPresentation, presentation)
      assertNotSame(presentation, current)
      assertTrue(service.isManualGraphDataRefreshRequired)

      fun contributionNames(index: BindingIndex): List<String> {
        val graph = index.graphs.single { it.name == "ContributionGraph" }
        val context = index.contextsFor(graph).single()
        val queryContext = checkNotNull(index.queryContext(context))
        return index.contributionsFor(queryContext).mapNotNull {
          it.classId?.shortClassName?.asString()
        }
      }

      // Query each generation only after the edit to verify a cold frozen result.
      assertEquals(listOf("ServiceImpl"), contributionNames(presentation))
      assertTrue(contributionNames(current).isEmpty())

      val refreshFinished = CompletableFuture<Unit>()
      service.addIndexListener(testRootDisposable) {
        if (!service.isManualGraphDataRefreshRequired) refreshFinished.complete(Unit)
      }
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(refreshFinished, 30_000)

      val refreshedPresentation = service.presentationIndex(graphFile)
      assertNotSame(presentation, refreshedPresentation)
      assertTrue(contributionNames(refreshedPresentation).isEmpty())
      assertFalse(service.isManualGraphDataRefreshRequired)
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testDisabledAutomaticRefreshFreezesColdExtensionContextsUntilManualRefresh() {
    val childFile =
      myFixture.addFileToProject(
        "test/ChildGraph.kt",
        """
        package test

        import dev.zacsweers.metro.GraphExtension

        abstract class ChildScope

        @GraphExtension(ChildScope::class)
        interface ChildGraph
        """
          .trimIndent(),
      ) as KtFile
    val parentFile =
      myFixture.addFileToProject(
        "test/ParentGraph.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val childGraph: ChildGraph
        }
        """
          .trimIndent(),
      ) as KtFile
    val service = project.service<MetroResolutionService>()
    val initialPresentation = service.awaitIndex(childFile)
    assertSame(initialPresentation, service.presentationIndex(childFile))
    assertEquals(
      setOf("ChildGraph", "ParentGraph"),
      initialPresentation.graphs.mapTo(mutableSetOf()) { it.name },
    )

    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()

    try {
      val creationAccessor = parentFile.declarationsIncludingNested().property("childGraph")
      WriteCommandAction.runWriteCommandAction(project) { creationAccessor.delete() }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val presentation = service.presentationIndex(childFile)
      val current = service.awaitIndex(childFile)
      assertSame(initialPresentation, presentation)
      assertNotSame(presentation, current)
      assertTrue(service.isManualGraphDataRefreshRequired)

      fun contextChains(index: BindingIndex): List<List<String>> {
        val child = index.graphs.single { it.name == "ChildGraph" }
        return index.contextsFor(child).map { context ->
          context.chain.map { graph -> checkNotNull(graph.name) }
        }
      }

      // Resolve parent contexts only after deleting the accessor.
      assertEquals(listOf(listOf("ChildGraph", "ParentGraph")), contextChains(presentation))
      assertEquals(listOf(listOf("ChildGraph")), contextChains(current))

      val refreshFinished = CompletableFuture<Unit>()
      service.addIndexListener(testRootDisposable) {
        if (!service.isManualGraphDataRefreshRequired) refreshFinished.complete(Unit)
      }
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(refreshFinished, 30_000)

      val refreshedPresentation = service.presentationIndex(childFile)
      assertNotSame(presentation, refreshedPresentation)
      assertEquals(listOf(listOf("ChildGraph")), contextChains(refreshedPresentation))
      assertFalse(service.isManualGraphDataRefreshRequired)
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testReenablingAutomaticRefreshBuildsForEarlierPresentationRequests() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.presentationIndex(file)
    val completed = CompletableFuture<Unit>()
    var buildStarted = false

    val progressUpdates =
      service.indexBuildProgress.collectInTest { progress ->
        if (progress != null) {
          buildStarted = true
        } else if (buildStarted) {
          completed.complete(Unit)
        }
      }

    try {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n\n@Inject class AddedAutomatically")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
      assertSame(BindingIndex.EMPTY, service.cachedIndex(file))

      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      PlatformTestUtil.waitForFuture(completed, 30_000)

      assertTrue(
        service.cachedIndex(file).bindings.any {
          it.typeKey.renderedType == "test.AddedAutomatically"
        }
      )
    } finally {
      progressUpdates.close()
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testReenablingAutomaticRefreshPublishesExplicitlyUpdatedData() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    service.activateGraphBrowser()
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()

    try {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n\n@Inject class AddedWhileManual")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val current = service.awaitIndex(file)
      assertTrue(service.isCurrent(current))
      assertTrue(current.bindings.any { it.typeKey.renderedType == "test.AddedWhileManual" })
      assertSame(initial, service.presentationIndex(file))

      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
      val resumed = CompletableFuture.runAsync {
        runBlocking { service.awaitCoordinatorBarrier() }
      }
      PlatformTestUtil.waitForFuture(resumed, 30_000)

      // The tool window uses the production background path even in unit tests.
      val presentation = service.indexForToolWindow(module)
      assertNotSame(BindingIndex.EMPTY, presentation)
      assertTrue(service.isCurrent(presentation))
      assertTrue(presentation.bindings.any { it.typeKey.renderedType == "test.AddedWhileManual" })
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testManualExplicitBuildPreservesOtherPublishedOptionSnapshots() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val settings = MetroSettings.getInstance(project).state
    val withLibraries = service.awaitIndex(file)

    try {
      settings.automaticallyRefreshGraphData = false
      service.settingsChanged()
      settings.resolveFromLibraries = false
      service.settingsChanged()

      val withoutLibraries = service.awaitIndex(file)
      assertNotSame(withLibraries, withoutLibraries)

      settings.resolveFromLibraries = true
      service.settingsChanged()

      assertSame(withLibraries, service.presentationIndex(file))
    } finally {
      settings.resolveFromLibraries = true
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testIndexBuildProgressReporterThrottlesIntermediateCounts() {
    var now = 0L
    val progress = mutableListOf<IndexBuildProgress>()
    val reporter =
      IndexBuildProgressReporter(
        publish = progress::add,
        updateIntervalNanos = 250,
        nanoTime = { now },
      )

    reporter.phase(IndexBuildPhase.DISCOVERING_SOURCE_FILES)
    reporter.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, 0, 10)
    reporter.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, 1, 10)
    now = 250
    reporter.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, 2, 10)
    reporter.counted(IndexBuildPhase.ANALYZING_DECLARATIONS, 10, 10)

    assertEquals(
      listOf(null, 0, 2, 10),
      progress.map { it.completed },
    )
  }

  fun testCoroutineCancellationStillStopsIndexBuildRetries() {
    val cancellation = CancellationException("project disposed")

    try {
      runBlocking { retryCancelledIndexBuild<String> { throw cancellation } }
      fail("Expected coroutine cancellation")
    } catch (failure: CancellationException) {
      assertSame(cancellation, failure)
    }
  }

  fun testExplicitReadActionWaitsOutsideTheReadForItsGeneration() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()

    try {
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n\n@Inject class AddedBackgroundWait")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val attempts = AtomicInteger()
      val ranOnEdt = AtomicBoolean()
      val ranWithoutReadAccess = AtomicBoolean()
      val updated = CompletableFuture.supplyAsync {
        runBlocking {
          retryCancelledIndexBuild {
            smartReadAction(project) {
              attempts.incrementAndGet()
              val application = ApplicationManager.getApplication()
              if (application.isDispatchThread) ranOnEdt.set(true)
              if (!application.isReadAccessAllowed) ranWithoutReadAccess.set(true)
              service.currentIndex(file)
            }
          }
        }
      }
      PlatformTestUtil.waitForFuture(updated, 30_000)

      assertTrue(attempts.get() >= 2)
      assertFalse(ranOnEdt.get())
      assertFalse(ranWithoutReadAccess.get())
      assertTrue(
        updated.join().bindings.any { it.typeKey.renderedType == "test.AddedBackgroundWait" }
      )
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testUnrelatedKotlinFileEditsPreserveTheExistingSnapshot() {
    val unrelated =
      myFixture.addFileToProject("test/Unrelated.kt", "package test\n\nclass Unrelated")
    val file = configure()
    val service = project.service<MetroResolutionService>()
    service.awaitIndex(file)
    myFixture.openFileInEditor(unrelated.virtualFile)
    // Enroll the file before recording the baseline.
    service.awaitIndex(unrelated)
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    val initial = service.awaitIndex(file)

    myFixture.editor.caretModel.moveToOffset(unrelated.textLength)
    myFixture.type("\nclass AlsoUnrelated")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    assertSame(initial, service.awaitIndex(file))
  }

  fun testReplacingAndRemovingUnrelatedClassesAndFilesPreservesTheExistingSnapshot() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val unrelated =
      myFixture.addFileToProject(
        "test/Unrelated.kt",
        "package test\n\nclass First\nclass Second",
      ) as KtFile
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    assertSame(initial, service.awaitIndex(file))

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(unrelated))
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText("package test\n\nclass Replacement")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    assertSame(initial, service.awaitIndex(file))

    WriteCommandAction.runWriteCommandAction(project) { unrelated.declarations.first().delete() }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    assertSame(initial, service.awaitIndex(file))

    WriteCommandAction.runWriteCommandAction(project) { unrelated.delete() }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    myFixture.addFileToProject("test/AfterDeletion.kt", "package test\n\nclass AfterDeletion")
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    assertSame(initial, service.awaitIndex(file))
  }

  fun testIrrelevantFileQueriesPreserveTheExistingSnapshot() {
    val unrelated =
      myFixture.addFileToProject("test/Unrelated.kt", "package test\n\nclass Unrelated") as KtFile
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)

    // Repeated queries from a non-Metro file must not invalidate anything.
    assertSame(initial, service.awaitIndex(unrelated))
    assertSame(initial, service.awaitIndex(unrelated))
    assertSame(initial, service.awaitIndex(file))
  }

  fun testManualRefreshCoalescesCreatedAndDeletedFiles() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()
    runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }
    try {
      val newFile =
        myFixture.addFileToProject(
          "test/NewIrrelevant.kt",
          "package test\n\n@dev.zacsweers.metro.Inject class NewIrrelevant",
        )
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      WriteCommandAction.runWriteCommandAction(project) { newFile.delete() }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      service.refreshGraphData()
      runBlocking { withTimeout(30_000) { service.awaitCoordinatorBarrier() } }

      val refreshed = service.presentationIndex(file)
      assertSame(initial.bindings.first(), refreshed.bindings.first())
      assertFalse(
        service.presentationIndex(file).bindings.any {
          it.typeKey.renderedType == "test.NewIrrelevant"
        }
      )
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testNestedTypeAliasesResolveWithExpandedKeysAndPreserveDisplayNames() {
    val file =
      myFixture.configureMetroFile(
        """
        class Box<T>
        typealias Text = String
        typealias Payload<T> = Box<T?>

        @DependencyGraph
        interface Graph {
          @Named("payload") val payload: Box<String?>?

          @Provides @Named("payload") fun providePayload(): Payload<Text>? = null
          @Provides @Named("other") fun provideOther(): Payload<Text>? = null
          @Provides @Named("payload") fun provideNonNullableElement(): Box<String>? = null
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val consumer = index.consumers.single()
    val binding =
      index.bindings.single {
        (it.pointer.element as? KtNamedDeclaration)?.name == "providePayload"
      }

    // Identity expands each alias while display text retains the declared spelling.
    assertEquals("test.Box<kotlin.String?>?", binding.typeKey.renderedType)
    assertEquals(consumer.key, binding.typeKey)
    assertEquals("Payload<Text>?", binding.typeKey.type.shortType)
    assertEquals("kotlin.String?", binding.typeKey.type.typeArguments.single().type!!.renderedType)
    assertEquals(listOf(binding), index.resolveConsumer(consumer).uniformBindings)

    val graph = index.graphs.single()
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(graph).single())
        .requireCompleted()
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testUnannotatedTypeAliasChangesRefreshDependentBindingKeys() {
    val aliases =
      myFixture.addFileToProject("test/Aliases.kt", "package test\n\ntypealias Alias = String")
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides fun provideAlias(): Alias = error("unused")
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    assertEquals(
      listOf("kotlin.String"),
      service.awaitIndex(file).bindings.map { it.typeKey.renderedType },
    )

    myFixture.openFileInEditor(aliases.virtualFile)
    val stringOffset = aliases.text.indexOf("String")
    myFixture.editor.selectionModel.setSelection(stringOffset, stringOffset + "String".length)
    myFixture.type("Int")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    assertEquals(
      listOf("kotlin.Int"),
      service.awaitIndex(file).bindings.map { it.typeKey.renderedType },
    )
  }

  fun testRemovingDirectoryWithSharedAliasesAndConstantsRefreshesDependents() {
    val shared =
      myFixture.addFileToProject(
        "test/shared/Definitions.kt",
        "package test\n\ntypealias Alias = String\nconst val SERVICE_NAME = \"before\"",
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(SERVICE_NAME) fun provideAlias(): Alias = error("unused")
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val classified = CompletableFuture<Unit>()
    service.addIndexListener(testRootDisposable) { classified.complete(Unit) }
    assertEquals(
      "kotlin.String",
      initial.bindings.single().typeKey.type.classId?.asFqNameString(),
    )

    WriteCommandAction.runWriteCommandAction(project) {
      checkNotNull(shared.containingDirectory).delete()
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    PlatformTestUtil.waitForFuture(classified, 30_000)

    val updated = service.awaitIndex(file)
    assertNotSame(initial, updated)
    assertTrue(
      "Removing shared declarations should invalidate the old resolved binding key",
      updated.bindings.none { it.typeKey.type.classId?.asFqNameString() == "kotlin.String" },
    )
  }

  fun testTypeAliasImportChangesRefreshDependentBindingKeys() {
    val aliases =
      myFixture.addFileToProject(
        "test/Aliases.kt",
        "package test\n\nimport kotlin.String as Value\n\ntypealias Alias = Value",
      )
    val providers =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides fun provideAlias(): Alias = error("unused")
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    assertEquals(
      "kotlin.String",
      service.awaitIndex(providers).bindings.single().typeKey.type.classId?.asFqNameString(),
    )
    myFixture.openFileInEditor(aliases.virtualFile)
    val stringOffset = aliases.text.indexOf("String")
    myFixture.editor.selectionModel.setSelection(stringOffset, stringOffset + "String".length)
    myFixture.type("Int")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    object : WaitFor(30_000) {
        override fun condition(): Boolean {
          return service
            .awaitIndex(providers)
            .bindings
            .single()
            .typeKey
            .type
            .classId
            ?.asFqNameString() == "kotlin.Int"
        }
      }
      .assertCompleted("The typealias import change should refresh its dependent binding key")

    assertEquals(
      "kotlin.Int",
      service.awaitIndex(providers).bindings.single().typeKey.type.classId?.asFqNameString(),
    )
  }

  fun testUnannotatedConstantChangesRefreshDependentBindingQualifiers() {
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        "package test\n\nconst val SERVICE_NAME = \"before\"",
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(initial.toString().contains("before"))

    myFixture.openFileInEditor(constants.virtualFile)
    val valueOffset = constants.text.indexOf("before")
    myFixture.editor.selectionModel.setSelection(valueOffset, valueOffset + "before".length)
    myFixture.type("after")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertNotSame(initial, updated)
    assertTrue(updated.toString().contains("after"))
  }

  fun testConstantImportAliasChangesRefreshDependentBindingQualifiers() {
    checkConstantImportChangesRefreshDependentBindingQualifiers(alias = true)
  }

  fun testConstantStarImportChangesRefreshDependentBindingQualifiers() {
    checkConstantImportChangesRefreshDependentBindingQualifiers(alias = false)
  }

  private fun checkConstantImportChangesRefreshDependentBindingQualifiers(alias: Boolean) {
    myFixture.addFileToProject(
      "values/first/Constants.kt",
      "package values.first\nconst val SERVICE_NAME = \"before\"",
    )
    myFixture.addFileToProject(
      "values/second/Constants.kt",
      "package values.second\nconst val SERVICE_NAME = \"after\"",
    )
    val imported = if (alias) "SERVICE_NAME as IMPORTED_NAME" else "*"
    val referenced = if (alias) "IMPORTED_NAME" else "SERVICE_NAME"
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        "package test\nimport values.first.$imported\nconst val PUBLIC_NAME = $referenced",
      )
    val providers =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(PUBLIC_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(providers).bindings.single().typeKey.qualifier
    assertTrue(initial.toString(), initial.toString().contains("before"))

    myFixture.openFileInEditor(constants.virtualFile)
    val importedPackageOffset = constants.text.indexOf("values.first")
    myFixture.editor.selectionModel.setSelection(
      importedPackageOffset,
      importedPackageOffset + "values.first".length,
    )
    myFixture.type("values.second")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated = service.awaitIndex(providers).bindings.single().typeKey.qualifier
    assertTrue(updated.toString(), updated.toString().contains("after"))
  }

  fun testUnannotatedNestedConstantChangesRefreshDependentBindingQualifiers() {
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        "package test\n\nobject Constants {\n  const val SERVICE_NAME = \"before\"\n}",
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(Constants.SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(initial.toString().contains("before"))

    myFixture.openFileInEditor(constants.virtualFile)
    val valueOffset = constants.text.indexOf("before")
    myFixture.editor.selectionModel.setSelection(valueOffset, valueOffset + "before".length)
    myFixture.type("after")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertNotSame(initial, updated)
    assertTrue(updated.toString().contains("after"))
  }

  fun testRemovingConstKeywordRefreshesDependentBindingQualifiers() {
    // The edit deletes the shared declaration itself, so only the pre-change tree shows it.
    // Before-events observing the cached answer are what catch this.
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        "package test\n\nconst val SERVICE_NAME = \"before\"",
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(initial.toString().contains("before"))

    myFixture.openFileInEditor(constants.virtualFile)
    val constOffset = constants.text.indexOf("const val")
    myFixture.editor.selectionModel.setSelection(constOffset, constOffset + "const val".length)
    myFixture.type("val")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(updated.toString(), updated?.toString()?.contains("before") != true)
  }

  fun testFileLevelReplacementPreservesRemovedSharedDeclarationContext() {
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        "package test\n\nobject Constants { const val SERVICE_NAME = \"before\" }",
      ) as KtFile
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(Constants.SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(initial.toString().contains("before"))
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(constants))
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText("package test\n\nclass Constants")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    object : WaitFor(30_000) {
        override fun condition(): Boolean {
          val qualifier = service.awaitIndex(file).bindings.single().typeKey.qualifier
          return qualifier?.toString()?.contains("before") != true
        }
      }
      .assertCompleted("Removing a shared declaration should refresh dependent qualifiers")

    val updated = service.awaitIndex(file).bindings.single().typeKey.qualifier
    assertTrue(updated.toString(), updated?.toString()?.contains("before") != true)
  }

  fun testConstantChangesInIndexedFilesRefreshDependentBindingQualifiers() {
    // The constant lives in a file that is itself indexed, so the dependent shard has no
    // recorded edge to it and relies on the shared-declaration fallback.
    val constants =
      myFixture.addFileToProject(
        "test/Constants.kt",
        """
        package test

        import dev.zacsweers.metro.Inject

        @Inject class Marker

        const val SERVICE_NAME = "before"
        """
          .trimIndent(),
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial =
      service
        .awaitIndex(file)
        .bindings
        .single { it.typeKey.renderedType == "kotlin.String" }
        .typeKey
    assertTrue(initial.toString().contains("before"))

    myFixture.openFileInEditor(constants.virtualFile)
    val valueOffset = constants.text.indexOf("before")
    myFixture.editor.selectionModel.setSelection(valueOffset, valueOffset + "before".length)
    myFixture.type("after")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service
        .awaitIndex(file)
        .bindings
        .single { it.typeKey.renderedType == "kotlin.String" }
        .typeKey
    assertTrue(updated.toString().contains("after"))
  }

  fun testUnrelatedEditsInConstantFilesDoNotRebuildOtherShards() {
    val mixed =
      myFixture.addFileToProject(
        "test/Mixed.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class Marker\n\n" +
          "const val SERVICE_NAME = \"unchanged\"",
      )
    val providers =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides @Named(SERVICE_NAME) fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val original =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }

    myFixture.openFileInEditor(mixed.virtualFile)
    myFixture.editor.caretModel.moveToOffset(
      mixed.text.indexOf("class Marker") + "class Marker".length
    )
    myFixture.type(" { fun unrelated() = 1 }")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }
    assertSame("An unrelated class edit must not force every shard to rebuild", original, updated)
  }

  fun testUnrelatedEditsInTypeAliasFilesDoNotRebuildOtherShards() {
    val mixed =
      myFixture.addFileToProject(
        "test/Mixed.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class Marker\n\n" + "typealias Alias = String",
      )
    val providers =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides fun provideAlias(): Alias = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val original =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }

    myFixture.openFileInEditor(mixed.virtualFile)
    myFixture.editor.caretModel.moveToOffset(
      mixed.text.indexOf("class Marker") + "class Marker".length
    )
    myFixture.type(" { fun unrelated() = 1 }")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }
    assertSame("An unrelated class edit must not force every shard to rebuild", original, updated)
  }

  fun testUnrelatedImportsInConstantFilesDoNotRebuildOtherShards() {
    checkUnrelatedImportsDoNotRebuildOtherShards("const val SERVICE_NAME = \"unchanged\"")
  }

  fun testUnrelatedImportsInTypeAliasFilesDoNotRebuildOtherShards() {
    checkUnrelatedImportsDoNotRebuildOtherShards("typealias Alias = String")
  }

  private fun checkUnrelatedImportsDoNotRebuildOtherShards(sharedDeclaration: String) {
    val mixed =
      myFixture.addFileToProject(
        "test/Mixed.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class Marker\n\n$sharedDeclaration",
      )
    val providers =
      myFixture.configureMetroFile(
        """
        interface Providers {
          @Provides fun provideService(): String = "service"
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val original =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }

    myFixture.openFileInEditor(mixed.virtualFile)
    // Auto-import inserts a complete directive. Partially typed imports keep conservative
    // invalidation.
    WriteCommandAction.runWriteCommandAction(project) {
      myFixture.editor.document.insertString(
        mixed.text.indexOf("@dev.zacsweers.metro.Inject"),
        "import dev.zacsweers.metro.ContributesBinding\n\n",
      )
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service.awaitIndex(providers).bindings.single {
        it.typeKey.type.classId?.asFqNameString() == "kotlin.String"
      }
    assertSame("An unused import must preserve bindings from unrelated shards", original, updated)
  }

  fun testSharedDeclarationFingerprintKeepsEnclosingHeaderImports() {
    val source =
      """
      package test
      import first.Parent
      object Owner : Parent() {
        const val NAME = "unchanged"
      }
      """
        .trimIndent()
    val factory = KtPsiFactory(project)
    val original = sharedDeclarationFingerprint(factory.createFile(source))
    val changed =
      sharedDeclarationFingerprint(
        factory.createFile(source.replace("first.Parent", "second.Parent"))
      )
    assertFalse(original == changed)
  }

  fun testSharedDeclarationFingerprintKeepsMalformedImports() {
    val source = "package test\nimport first.Missing as\nconst val NAME = \"unchanged\""
    val factory = KtPsiFactory(project)
    val original = sharedDeclarationFingerprint(factory.createFile(source))
    val changed =
      sharedDeclarationFingerprint(
        factory.createFile(source.replace("first.Missing", "second.Missing"))
      )
    assertFalse(original == changed)
  }

  fun testSharedDeclarationFingerprintIgnoresImportOrderAndWhitespace() {
    val original =
      """
      package test
      import kotlin.String as Value
      import kotlin.Int as Number
      typealias Alias = Pair<Value, Number>
      """
        .trimIndent()
    val reordered =
      """
      package test
      import kotlin.Int as Number
      import kotlin.String  as  Value
      typealias Alias = Pair<Value, Number>
      """
        .trimIndent()
    val factory = KtPsiFactory(project)
    assertEquals(
      sharedDeclarationFingerprint(factory.createFile(original)),
      sharedDeclarationFingerprint(factory.createFile(reordered)),
    )
  }

  fun testIncrementalShardReplacementPreservesDeclarationOrder() {
    val first =
      myFixture.addFileToProject(
        "test/First.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class First",
      )
    myFixture.addFileToProject(
      "test/Second.kt",
      "package test\n\n@dev.zacsweers.metro.Inject class Second",
    )
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val original = service.awaitIndex(file).bindings.map { it.typeKey.renderedType }

    myFixture.openFileInEditor(first.virtualFile)
    myFixture.editor.caretModel.moveToOffset(first.textLength)
    myFixture.type(" { fun unrelated() = 1 }")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    assertEquals(original, service.awaitIndex(file).bindings.map { it.typeKey.renderedType })
  }

  fun testRemovingOneSharedDependencyOwnerKeepsOtherOwnersCurrent() {
    val dependency =
      myFixture.addFileToProject(
        "test/BaseGraph.kt",
        "package test\n\ninterface BaseGraph { val value: String }",
      )
    val first =
      myFixture.addFileToProject(
        "test/FirstGraph.kt",
        "package test\n\n@dev.zacsweers.metro.DependencyGraph " +
          "interface FirstGraph : BaseGraph",
      )
    val second = myFixture.configureMetroFile("@DependencyGraph interface SecondGraph : BaseGraph")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(second)
    val initialGraph = initial.graphs.single { it.name == "SecondGraph" }
    assertEquals(
      listOf("kotlin.String"),
      initial.accessorsFor(initialGraph).map { it.key.renderedType },
    )

    myFixture.openFileInEditor(first.virtualFile)
    val supertypeOffset = first.text.indexOf(" : BaseGraph")
    myFixture.editor.selectionModel.setSelection(
      supertypeOffset,
      supertypeOffset + " : BaseGraph".length,
    )
    myFixture.type(" ")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val detached = service.awaitIndex(second)
    val detachedFirstGraph = detached.graphs.single { it.name == "FirstGraph" }
    val analyzingTotal = CompletableFuture<Int>()

    val progressUpdates =
      service.indexBuildProgress.collectInTest { progress ->
        if (progress?.phase == IndexBuildPhase.ANALYZING_DECLARATIONS && progress.completed == 0) {
          progress.total?.let(analyzingTotal::complete)
        }
      }

    try {
      myFixture.openFileInEditor(dependency.virtualFile)
      val stringOffset = dependency.text.indexOf("String")
      myFixture.editor.selectionModel.setSelection(stringOffset, stringOffset + "String".length)
      myFixture.type("Int")
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(second)
      val updatedGraph = updated.graphs.single { it.name == "SecondGraph" }
      assertEquals(
        listOf("kotlin.Int"),
        updated.accessorsFor(updatedGraph).map { it.key.renderedType },
      )
      assertEquals(2, PlatformTestUtil.waitForFuture(analyzingTotal, 30_000))
      assertSame(detachedFirstGraph, updated.graphs.single { it.name == "FirstGraph" })
    } finally {
      progressUpdates.close()
    }
  }

  fun testOutputOnlyCompilerOptionsPreserveTheExistingSnapshot() {
    project.setMetroOptions("reports-destination" to "/tmp/metro-first")
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)

    project.setMetroOptions(
      "reports-destination" to "/tmp/metro-second",
      "trace-destination" to "/tmp/metro-traces",
    )

    assertSame(initial, service.awaitIndex(file))
  }

  fun testGraphValidationCompilerOptionsInvalidateTheExistingSnapshot() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)

    project.setMetroOptions("enable-suspend-providers" to "true")
    val suspendEnabled = service.awaitIndex(file)
    assertNotSame(initial, suspendEnabled)

    project.setMetroOptions(
      "enable-suspend-providers" to "true",
      "enable-function-providers" to "false",
    )
    val functionProvidersDisabled = service.awaitIndex(file)
    assertNotSame(suspendEnabled, functionProvidersDisabled)

    project.setMetroOptions(
      "enable-suspend-providers" to "true",
      "enable-function-providers" to "false",
      "shrink-unused-bindings" to "false",
    )
    assertNotSame(functionProvidersDisabled, service.awaitIndex(file))
  }

  fun testNewlyAnnotatedFilesAreAddedWithoutRebuildingUnchangedDeclarations() {
    val additional = myFixture.addFileToProject("test/Additional.kt", "package test\n\nclass Added")
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val unchanged = initial.bindings.single { it.typeKey.renderedType == "test.ServiceImpl" }

    myFixture.openFileInEditor(additional.virtualFile)
    myFixture.editor.caretModel.moveToOffset(additional.text.indexOf("class Added"))
    myFixture.type("@dev.zacsweers.metro.Inject ")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    object : WaitFor(30_000) {
        override fun condition(): Boolean {
          return service.awaitIndex(file).bindings.any { it.typeKey.renderedType == "test.Added" }
        }
      }
      .assertCompleted("The newly annotated file should join the background index")

    val updated = service.awaitIndex(file)
    assertNotSame(initial, updated)
    assertTrue(updated.bindings.any { it.typeKey.renderedType == "test.Added" })
    assertSame(unchanged, updated.bindings.single { it.typeKey.renderedType == "test.ServiceImpl" })
  }

  fun testRemovingTheLastRelevantAnnotationDropsItsFileShard() {
    val additional =
      myFixture.addFileToProject(
        "test/Temporary.kt",
        "package test\n\n@dev.zacsweers.metro.Inject class Temporary",
      )
    val file = configure()
    val service = project.service<MetroResolutionService>()
    assertTrue(
      service.awaitIndex(file).bindings.any { it.typeKey.renderedType == "test.Temporary" }
    )

    myFixture.openFileInEditor(additional.virtualFile)
    myFixture.editor.selectionModel.setSelection(
      additional.text.indexOf("@dev.zacsweers.metro.Inject "),
      additional.text.indexOf("class Temporary"),
    )
    myFixture.type(" ")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    assertFalse(
      service.awaitIndex(file).bindings.any { it.typeKey.renderedType == "test.Temporary" }
    )
  }

  fun testEditorDecorationSettingsDoNotInvalidateTheIndex() {
    val file = configure()
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val settings = MetroSettings.getInstance(project).state
    settings.enableBindingResolution = false
    try {
      service.settingsChanged()

      assertSame(initial, service.awaitIndex(file))
    } finally {
      settings.enableBindingResolution = true
    }
  }

  private fun configure(): KtFile {
    return myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.AppScope
      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.IntoMap
      import dev.zacsweers.metro.Named
      import dev.zacsweers.metro.Provides
      import dev.zacsweers.metro.SingleIn
      import dev.zacsweers.metro.StringKey

      interface Service
      interface HttpApi
      interface Analytics

      @Inject class ServiceImpl : Service

      interface ServiceBindings {
        @Binds fun bindService(impl: ServiceImpl): Service
      }

      @ContributesBinding(AppScope::class)
      @SingleIn(AppScope::class)
      class RealHttpApi : HttpApi

      @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics
      @ContributesIntoSet(AppScope::class) class ProdAnalytics : Analytics

      interface UrlProviders {
        @Provides @Named("cdn") fun provideCdnUrl(): String = "cdn"
        @Provides fun provideBaseUrl(): String = "base"
      }

      interface HandlerProviders {
        @Provides @IntoMap @StringKey("a") fun handlerA(): Service = ServiceImpl()
        @Provides @IntoMap @StringKey("b") fun handlerB(): Service = ServiceImpl()
      }

      @Inject
      class Consumer(
        val service: Service,
        val api: HttpApi,
        val analytics: Set<Analytics>,
        val handlers: Map<String, Service>,
        @Named("cdn") val cdnUrl: String,
      )

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }
      """
        .trimIndent(),
    ) as KtFile
  }

  fun testBindsBindingIsIndexedWithImplementation() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.function("bindService")).single()
    assertEquals("binds", entry.label)
    assertEquals("test.Service", entry.typeKey.renderedType)
    assertEquals("ServiceImpl", entry.implementationName)

    // The @Binds impl parameter consumes the impl binding
    val implParam = declarations.parameter("impl")
    assertEquals("test.ServiceImpl", index.consumerEntryAt(implParam)?.key?.renderedType)
  }

  fun testInjectedClassProvidesItsOwnTypeAndConsumesConstructorParams() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entry = index.bindingEntriesAt(declarations.klass("Consumer")).single()
    assertEquals("injected class", entry.label)
    assertEquals("test.Consumer", entry.typeKey.renderedType)

    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals("test.Service", serviceParam.key.renderedType)
    assertTrue(serviceParam.isAbstractType)

    // The consumer's Service key resolves to the @Binds provider
    val bindings = index.bindingsFor(serviceParam)
    assertEquals(listOf("binds"), bindings.map { it.label })
  }

  fun testContributedBindingBindsItsSoleSupertypeWithScope() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val entries = index.bindingEntriesAt(declarations.klass("RealHttpApi"))
    val contributed = entries.single { it.label == "contributed binding" }
    assertEquals("test.HttpApi", contributed.typeKey.renderedType)
    assertEquals("RealHttpApi", contributed.implementationName)
    assertEquals("@SingleIn(scope = AppScope::class)", contributed.scope?.render(short = true))

    val apiParam = index.consumerEntryAt(declarations.parameter("api"))!!
    assertEquals(listOf("RealHttpApi"), index.bindingsFor(apiParam).map { it.implementationName })
  }

  fun testSetMultibindingContributionsJoinTheirMultibindingConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val analyticsParam = index.consumerEntryAt(declarations.parameter("analytics"))!!
    assertEquals("kotlin.collections.Set<test.Analytics>", analyticsParam.key.renderedType)
    assertEquals("test.Analytics", analyticsParam.multibindingId)

    // Contributions keep their element key, mirroring the compiler's @MultibindingElement model
    val contributors = index.bindingsFor(analyticsParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.label == "multibinding contribution" })
    assertTrue(contributors.all { it.typeKey.renderedType == "test.Analytics" })

    // And the reverse direction: a contribution's consumers include the multibinding site
    val debugAnalytics = index.bindingEntriesAt(declarations.klass("DebugAnalytics"))
    val consumers = index.consumersFor(debugAnalytics)
    assertTrue(consumers.any { it.pointer.element === declarations.parameter("analytics") })
  }

  fun testMapMultibindingContributionsJoinTheirMultibindingConsumer() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val handlersParam = index.consumerEntryAt(declarations.parameter("handlers"))!!
    assertEquals(
      "kotlin.collections.Map<kotlin.String, test.Service>",
      handlersParam.key.renderedType,
    )
    assertEquals("kotlin.String_test.Service", handlersParam.multibindingId)

    val contributors = index.bindingsFor(handlersParam)
    assertEquals(2, contributors.size)
    assertTrue(contributors.all { it.label == "multibinding contribution" })
    assertEquals(
      setOf("handlerA", "handlerB"),
      contributors.mapNotNull { (it.pointer.element as? KtNamedDeclaration)?.name }.toSet(),
    )

    // The plain Service consumer is not polluted by map contributions
    val serviceParam = index.consumerEntryAt(declarations.parameter("service"))!!
    assertEquals(listOf("binds"), index.bindingsFor(serviceParam).map { it.label })
  }

  fun testQualifiersDisambiguateKeys() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val cdnParam = index.consumerEntryAt(declarations.parameter("cdnUrl"))!!
    assertEquals("@Named(name = \"cdn\") String", cdnParam.key.render(short = true))
    assertEquals(
      "@dev.zacsweers.metro.Named(name = \"cdn\") kotlin.String",
      cdnParam.key.render(short = false),
    )

    val bindings = index.bindingsFor(cdnParam)
    assertEquals(1, bindings.size)
    assertEquals("provideCdnUrl", (bindings.single().pointer.element as? KtNamedDeclaration)?.name)
  }

  fun testGetterQualifiersDisambiguateSourceGraphAccessorsAndProviders() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          @get:Named("getter") val getterQualified: String
          @Named("property") val propertyQualified: String
          val unqualified: String

          @Provides @get:Named("getter") val getterProvider: String get() = "getter"
          @Provides @Named("property") val propertyProvider: String get() = "property"
          @Provides val unqualifiedProvider: String get() = "plain"
        }
        """
      )
    val settings = MetroSettings.getInstance(project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
      val expected =
        mapOf(
          "getterQualified" to ("@Named(name = \"getter\") String" to "getterProvider"),
          "propertyQualified" to ("@Named(name = \"property\") String" to "propertyProvider"),
          "unqualified" to ("String" to "unqualifiedProvider"),
        )
      for ((name, expectation) in expected) {
        val accessor =
          index.accessorsFor(query).single {
            (it.pointer.element as? KtNamedDeclaration)?.name == name
          }
        assertEquals(expectation.first, accessor.key.render(short = true))
        val bindings = index.bindingsFor(accessor, query)
        assertEquals(
          listOf(expectation.second),
          bindings.map { (it.pointer.element as? KtNamedDeclaration)?.name },
        )
        assertEquals(listOf(accessor.key), bindings.map { it.typeKey })
      }
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testInheritedGetterQualifiersPreserveConcreteGraphKeys() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Accessors<T> {
          @get:Named("inherited") val inherited: T
        }

        interface Providers<T> {
          @Provides @get:Named("inherited") val inheritedProvider: T get() = error("fixture")
          @Provides val unqualifiedProvider: T get() = error("fixture")
        }

        @DependencyGraph
        interface AppGraph : Accessors<String>, Providers<String> {
          val unqualified: String
        }
        """
      )
    val settings = MetroSettings.getInstance(project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
      val expected =
        mapOf(
          "inherited" to ("@Named(name = \"inherited\") String" to "inheritedProvider"),
          "unqualified" to ("String" to "unqualifiedProvider"),
        )
      for ((name, expectation) in expected) {
        val accessor =
          index.accessorsFor(query).single {
            (it.pointer.element as? KtNamedDeclaration)?.name == name
          }
        assertEquals(graph.declarationId, accessor.graphId)
        assertEquals(expectation.first, accessor.key.render(short = true))
        val bindings = index.bindingsFor(accessor, query)
        assertEquals(
          listOf(expectation.second),
          bindings.map { (it.pointer.element as? KtNamedDeclaration)?.name },
        )
        assertEquals(listOf(accessor.key), bindings.map { it.typeKey })
      }
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testContributedGetterQualifiersPreserveExactGraphLookup() {
    val file =
      myFixture.configureMetroFile(
        """
        object GetterScope

        @ContributesTo(GetterScope::class)
        interface GetterMembers {
          @get:Named("contributed") val contributed: String
          @Provides @get:Named("contributed") val contributedProvider: String get() = "qualified"
          @Provides val unqualifiedProvider: String get() = "plain"
        }

        @DependencyGraph(GetterScope::class)
        interface AppGraph {
          val unqualified: String
        }
        """
      )
    val settings = MetroSettings.getInstance(project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
      val expected =
        mapOf(
          "contributed" to ("@Named(name = \"contributed\") String" to "contributedProvider"),
          "unqualified" to ("String" to "unqualifiedProvider"),
        )
      for ((name, expectation) in expected) {
        val accessor =
          index.accessorsFor(query).single {
            (it.pointer.element as? KtNamedDeclaration)?.name == name
          }
        assertEquals(graph.declarationId, accessor.graphId)
        assertEquals(expectation.first, accessor.key.render(short = true))
        val bindings = index.bindingsFor(accessor, query)
        assertEquals(
          listOf(expectation.second),
          bindings.map { (it.pointer.element as? KtNamedDeclaration)?.name },
        )
        assertEquals(listOf(accessor.key), bindings.map { it.typeKey })
      }
      val result =
        project
          .service<MetroGraphValidationService>()
          .validate(file, query.graphContext)
          .requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testGetterQualifierDefaultChangesRefreshDependentGraphKeys() {
    val qualifier =
      myFixture.addFileToProject(
        "test/Endpoint.kt",
        """
        package test

        import dev.zacsweers.metro.Qualifier

        @Qualifier
        @Target(AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FUNCTION)
        annotation class Endpoint(val name: String = "main")
        """
          .trimIndent(),
      )
    myFixture.addFileToProject(
      "test/EndpointProviders.kt",
      """
      package test

      import dev.zacsweers.metro.BindingContainer
      import dev.zacsweers.metro.Provides

      @BindingContainer
      object EndpointProviders {
        @Provides @Endpoint("main") fun provideMain(): String = "main"
        @Provides @Endpoint("other") fun provideOther(): String = "other"
      }
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph(bindingContainers = [EndpointProviders::class])
        interface AppGraph {
          @get:Endpoint val endpoint: String
        }
        """,
        fileName = "GetterQualifierGraph.kt",
      )
    val settings = MetroSettings.getInstance(project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      val accessor = file.declarationsIncludingNested().property("endpoint")
      val initialConsumer = checkNotNull(initial.consumerEntryAt(accessor))
      assertEquals("@Endpoint(name = \"main\") String", initialConsumer.key.render(short = true))
      assertEquals(
        listOf("provideMain"),
        initial.resolveConsumer(initialConsumer).uniformBindings.orEmpty().map {
          (it.pointer.element as? KtNamedDeclaration)?.name
        },
      )

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(qualifier))
      val defaultOffset = document.text.indexOf("\"main\"")
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(defaultOffset, defaultOffset + "\"main\"".length, "\"other\"")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(file)
      val updatedConsumer = checkNotNull(updated.consumerEntryAt(accessor))
      assertNotSame(initial, updated)
      assertEquals("@Endpoint(name = \"other\") String", updatedConsumer.key.render(short = true))
      assertEquals(
        listOf("provideOther"),
        updated.resolveConsumer(updatedConsumer).uniformBindings.orEmpty().map {
          (it.pointer.element as? KtNamedDeclaration)?.name
        },
      )
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testConcreteInjectedClassConsumersAcrossInjectionShapes() {
    project.setMetroOptions("enable-top-level-function-injection" to "true")
    val file =
      myFixture.configureByText(
        "Shapes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.Assisted
        import dev.zacsweers.metro.AssistedInject
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        @Inject @SingleIn(AppScope::class) class Repository

        @Inject fun HomePresenter(repository: Repository): Int = 0

        @AssistedInject class DetailPresenter(@Assisted val id: String, val repo: Repository)

        @DependencyGraph(AppScope::class)
        interface ShapeGraph {
          val repository: Repository
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val repositoryEntries = index.bindingEntriesAt(declarations.klass("Repository"))
    assertEquals(listOf("injected class"), repositoryEntries.map { it.label })

    val consumerElements =
      index.consumersFor(repositoryEntries).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(setOf("repository", "repo"), consumerElements.toSet())
    // 3 sites: the injected function param, the assisted class param, and the graph accessor
    assertEquals(3, consumerElements.size)

    // The @Assisted param is marked as supplied at creation time, not as a consumer
    val idParam = declarations.parameter("id")
    assertNull(index.consumerEntryAt(idParam))
    assertEquals("@Assisted", index.assistedSiteAt(idParam)?.supplier)
  }

  fun testGraphEntryExposesScopesAccessorsAndContributions() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    assertEquals(setOf(ClassId.fromString("dev/zacsweers/metro/AppScope")), graph.scopeKeys)

    // The accessor property is a consumer of Consumer
    val accessor = index.consumerEntryAt(declarations.property("consumer"))!!
    assertEquals("test.Consumer", accessor.key.renderedType)

    val contributions =
      index.contributionsForScopes(graph.scopeKeys).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(
      setOf("RealHttpApi", "DebugAnalytics", "ProdAnalytics"),
      contributions.toSet(),
    )
  }

  fun testCircuitParameterResolvesSingleContributedImplementation() {
    project.setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.addCircuitStubs()
    val file =
      myFixture.configureByText(
        "CircuitImpl.kt",
        """
        package test

        import com.slack.circuit.codegen.annotations.CircuitInject
        import com.slack.circuit.runtime.CircuitUiState
        import com.slack.circuit.runtime.screen.Screen
        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        class AreaScreen : Screen
        class AreaState : CircuitUiState

        interface Repo

        @SingleIn(AppScope::class)
        @ContributesBinding(AppScope::class)
        class RepoImpl(private val name: String) : Repo {
          @Inject constructor(count: Int) : this(count.toString())
        }

        @CircuitInject(AreaScreen::class, AppScope::class)
        fun AreaPresenter(screen: AreaScreen, repo: Repo): AreaState {
          return AreaState()
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // The exact inputs the implementation inlay needs
    val consumer = index.consumerEntryAt(declarations.parameter("repo"))!!
    assertTrue(consumer.isAbstractType)
    val bindings = index.bindingsFor(consumer)
    assertEquals(1, bindings.size)
    assertEquals("RepoImpl", bindings.single().implementationName)
  }

  fun testCircuitInjectDeclarationsContributeFactoriesAndConsumeParameters() {
    project.setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.addCircuitStubs()
    val file =
      myFixture.configureByText(
        "Circuit.kt",
        """
        package test

        import androidx.compose.ui.Modifier
        import com.slack.circuit.codegen.annotations.CircuitInject
        import com.slack.circuit.runtime.CircuitUiState
        import com.slack.circuit.runtime.Navigator
        import com.slack.circuit.runtime.presenter.Presenter
        import com.slack.circuit.runtime.screen.Screen
        import com.slack.circuit.runtime.ui.Ui
        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        abstract class OtherScope

        class HomeScreen : Screen
        class HomeState : CircuitUiState

        @Inject class Repository

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun HomePresenter(repository: Repository, navigator: Navigator, screen: HomeScreen): HomeState {
          return HomeState()
        }

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun HomeUi(state: HomeState, modifier: Modifier, repository: Repository) {
        }

        @DependencyGraph(AppScope::class)
        interface CircuitGraph {
          val uiFactories: Set<Ui.Factory>
          val presenterFactories: Set<Presenter.Factory>
        }

        @DependencyGraph(OtherScope::class)
        interface OtherCircuitGraph {
          val otherUiFactories: Set<Ui.Factory>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // Both functions contribute generated factories into the scope's factory sets
    val presenterEntry = index.bindingEntriesAt(declarations.function("HomePresenter")).single()
    assertEquals("multibinding contribution", presenterEntry.label)
    assertEquals(
      "com.slack.circuit.runtime.presenter.Presenter.Factory",
      presenterEntry.typeKey.renderedType,
    )

    val uiEntry = index.bindingEntriesAt(declarations.function("HomeUi")).single()
    assertEquals("com.slack.circuit.runtime.ui.Ui.Factory", uiEntry.typeKey.renderedType)
    assertEquals(
      setOf(ClassId.topLevel(FqName("dev.zacsweers.metro.AppScope"))),
      uiEntry.contributionScopes,
    )

    // The graph's factory set accessors resolve to the contributions
    val presenterFactories = index.consumerEntryAt(declarations.property("presenterFactories"))!!
    assertEquals(
      listOf("HomePresenter"),
      index.bindingsFor(presenterFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    val uiFactories = index.consumerEntryAt(declarations.property("uiFactories"))!!
    assertEquals(
      listOf("HomeUi"),
      index.bindingsFor(uiFactories).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )

    val otherGraph =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherCircuitGraph"))!!).single()
    val otherUiFactories = index.consumerEntryAt(declarations.property("otherUiFactories"))!!
    assertTrue(index.bindingsFor(otherUiFactories, index.queryContext(otherGraph)!!).isEmpty())

    // Injected params are consumers; circuit-provided params (navigator/screen/state/modifier)
    // are assisted sites instead
    val repositoryEntries = index.bindingEntriesAt(declarations.klass("Repository"))
    val repositoryConsumers =
      index.consumersFor(repositoryEntries).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertEquals(listOf("repository", "repository"), repositoryConsumers.sorted())
    for (name in listOf("navigator", "screen", "state", "modifier")) {
      val parameter = declarations.parameter(name)
      assertNull(index.consumerEntryAt(parameter))
      assertEquals("Circuit", index.assistedSiteAt(parameter)?.supplier)
    }

    // And both declarations show up as contributions to the graph's scope
    val graph = index.graphEntryAt(declarations.klass("CircuitGraph"))!!
    val contributionNames =
      index.contributionsForScopes(graph.scopeKeys).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      }
    assertTrue(contributionNames.containsAll(listOf("HomePresenter", "HomeUi")))
  }

  fun testGraphFactoryInstanceBindingsResolve() {
    val file =
      myFixture.configureByText(
        "Factory.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.Provides

        class Config

        @Inject class ConfigConsumer(val config: Config)

        @DependencyGraph(AppScope::class)
        interface FactoryGraph {
          val consumer: ConfigConsumer

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Provides providedConfig: Config): FactoryGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // The factory's @Provides param is an instance binding, not a consumer
    val factoryParam = declarations.parameter("providedConfig")
    assertNull(index.consumerEntryAt(factoryParam))
    val instanceEntry = index.bindingEntriesAt(factoryParam).single()
    assertEquals("instance binding", instanceEntry.label)
    assertEquals("test.Config", instanceEntry.typeKey.renderedType)

    // And consumers of its type resolve to it
    val configParam = index.consumerEntryAt(declarations.parameter("config"))!!
    val bindings = index.bindingsFor(configParam)
    assertEquals(listOf("instance binding"), bindings.map { it.label })
    assertTrue(bindings.single().pointer.element === factoryParam)
  }

  fun testLibraryInjectClassesResolveOnDemand() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibConsumer.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibHttpClient

          @Inject class LibConsumer(val client: LibHttpClient)
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      val bindings = index.bindingsFor(clientParam)
      assertEquals(listOf("injected class"), bindings.map { it.label })
      val target = bindings.single().pointer.element
      assertEquals("LibHttpClient", (target as? KtNamedDeclaration)?.name)
      assertEquals(
        "@SingleIn(scope = AppScope::class)",
        bindings.single().scope?.render(short = true),
      )
    }
  }

  fun testBinaryGenericSupertypeProvidersStayInTheirOwningGraph() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibGenericBase

          @DependencyGraph
          interface StringGraph : LibGenericBase<String> {
            val stringValue: String
          }

          @DependencyGraph
          interface IntGraph : LibGenericBase<Int> {
            val intValue: Int
          }
          """,
          fileName = "BinaryGenericGraphs.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()
      val stringAccessor = index.consumerEntryAt(declarations.property("stringValue"))!!
      val intAccessor = index.consumerEntryAt(declarations.property("intValue"))!!

      assertEquals(
        listOf("kotlin.String"),
        index.resolveConsumer(stringAccessor).uniformBindings.orEmpty().map {
          it.typeKey.renderedType
        },
      )
      assertEquals(
        listOf("kotlin.Int"),
        index.resolveConsumer(intAccessor).uniformBindings.orEmpty().map {
          it.typeKey.renderedType
        },
      )
    }
  }

  fun testBinaryContributedInterfaceMembersResolveInTheirOwningGraph() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibInterfaceScope

          @ContributesTo(LibInterfaceScope::class)
          interface LocalPart {
            val local: Int
            @Provides fun provideLocal(): Int = 1
          }

          @DependencyGraph(LibInterfaceScope::class)
          interface AppGraph

          @DependencyGraph
          interface OtherGraph
          """,
          fileName = "BinaryInterfaceGraph.kt",
        )
      val service = project.service<MetroResolutionService>()
      val index = service.awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
      val composition = index.graphComposition(query)
      assertEquals(
        setOf("kotlin.String", "kotlin.Int", "libtest.LibInterfaceClient"),
        composition.accessors.map { it.key.renderedType }.toSet(),
      )
      assertTrue(composition.accessors.any { it.injectedMemberPointer != null })
      assertFalse(
        index.contributions.any { it.classId?.shortClassName?.asString() == "LibHiddenGraph" }
      )
      val value =
        composition.accessors.single {
          (it.pointer.element as? KtNamedDeclaration)?.name == "value"
        }
      val provider = index.bindingsFor(value).single()
      assertEquals("provideValue", (provider.pointer.element as? KtNamedDeclaration)?.name)
      assertEquals(graph.declarationId, provider.ownerGraphId)
      assertEquals(
        listOf("libtest.LibInterfaceDependency"),
        provider.dependencies.map { it.typeKey.renderedType },
      )
      assertTrue(index.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceClient" })
      assertTrue(index.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceDependency" })

      val otherGraph = index.graphs.single { it.name == "OtherGraph" }
      val otherQuery = checkNotNull(index.queryContext(index.contextsFor(otherGraph).single()))
      assertTrue(index.graphComposition(otherQuery).accessors.isEmpty())
      val result =
        project
          .service<MetroGraphValidationService>()
          .validate(file, query.graphContext)
          .requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val updated = service.awaitIndex(file)
      val updatedGraph = updated.graphs.single { it.name == "AppGraph" }
      val updatedQuery =
        checkNotNull(updated.queryContext(updated.contextsFor(updatedGraph).single()))
      val updatedAccessors = updated.graphComposition(updatedQuery).accessors
      val updatedValue = updatedAccessors.single {
        (it.pointer.element as? KtNamedDeclaration)?.name == "value"
      }
      assertSame(provider, updated.bindingsFor(updatedValue).single())
      val local = updatedAccessors.single { it.key.renderedType == "kotlin.Int" }
      assertEquals(
        "provideLocal",
        (updated.bindingsFor(local, updatedQuery).single().pointer.element as? KtNamedDeclaration)
          ?.name,
      )
    }
  }

  fun testBinaryContributedInterfaceExclusionEditsRefreshItsDependencies() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibContributedGraph
          import libtest.LibInterfaceScope

          @DependencyGraph(LibInterfaceScope::class, excludes = [LibContributedGraph::class])
          interface AppGraph
          """,
          fileName = "ExcludedBinaryInterface.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      val initialQuery =
        checkNotNull(initial.queryContext(initial.contextsFor(initial.graphs.single()).single()))
      assertTrue(initial.graphComposition(initialQuery).accessors.isEmpty())
      assertFalse(initial.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceClient" })
      assertFalse(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceDependency" }
      )

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      val exclusion = ", excludes = [LibContributedGraph::class]"
      val offset = document.text.indexOf(exclusion)
      WriteCommandAction.runWriteCommandAction(project) {
        document.deleteString(offset, offset + exclusion.length)
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val updated = service.awaitIndex(file)
      val query =
        checkNotNull(updated.queryContext(updated.contextsFor(updated.graphs.single()).single()))
      assertEquals(
        setOf("kotlin.String", "libtest.LibInterfaceClient"),
        updated.graphComposition(query).accessors.map { it.key.renderedType }.toSet(),
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceClient" })
      assertTrue(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceDependency" }
      )
      val result =
        project
          .service<MetroGraphValidationService>()
          .validate(file, query.graphContext)
          .requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testSourceReplacementRemovesBinaryContributedMembers() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibContributedGraph
          import libtest.LibInterfaceScope

          @ContributesTo(LibInterfaceScope::class, replaces = [LibContributedGraph::class])
          interface Replacement {
            val enabled: Boolean
            @Provides fun provideEnabled(): Boolean = true
          }

          @DependencyGraph(LibInterfaceScope::class)
          interface AppGraph
          """,
          fileName = "ReplacedBinaryInterface.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val query =
        checkNotNull(index.queryContext(index.contextsFor(index.graphs.single()).single()))
      val accessors = index.graphComposition(query).accessors
      assertEquals(listOf("kotlin.Boolean"), accessors.map { it.key.renderedType })
      assertEquals(
        "provideEnabled",
        (index.bindingsFor(accessors.single(), query).single().pointer.element
            as? KtNamedDeclaration)
          ?.name,
      )
      assertFalse(index.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceClient" })
      val result =
        project
          .service<MetroGraphValidationService>()
          .validate(file, query.graphContext)
          .requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testBinaryContributedInterfacesRespectLibraryResolutionSetting() {
    val settings = MetroSettings.getInstance(project).state
    settings.resolveFromLibraries = false
    try {
      module.withMetroLibFixtureLibrary {
        val file =
          myFixture.configureMetroFile(
            """
            import libtest.LibInterfaceScope

            @DependencyGraph(LibInterfaceScope::class)
            interface AppGraph
            """,
            fileName = "DisabledBinaryInterfaces.kt",
          )
        val index = project.service<MetroResolutionService>().awaitIndex(file)
        val query =
          checkNotNull(index.queryContext(index.contextsFor(index.graphs.single()).single()))
        assertTrue(index.graphComposition(query).accessors.isEmpty())
        assertTrue(index.graphs.single().contributedInterfaces.isEmpty())
      }
    } finally {
      settings.resolveFromLibraries = true
    }
  }

  fun testBinaryContributedExtensionsResolveNestedGraphsAndFactoryInputs() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibChildGraph
          import libtest.LibChildScope
          import libtest.LibParentScope
          import libtest.LibParentService

          @Inject class SourceBox<T>(val value: T)

          @ContributesTo(LibChildScope::class)
          interface ChildMembers {
            val box: SourceBox<String>
          }

          @DependencyGraph(LibParentScope::class)
          interface AppGraph {
            val factory: LibChildGraph.Factory
            @Provides fun parent(): LibParentService = object : LibParentService {}
          }
          """,
          fileName = "BinaryChildGraphs.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      assertFalse(index.graphs.any { it.name == "LibHiddenChildGraph" })
      val child = index.graphs.single { it.name == "LibChildGraph" }
      val childContext = index.contextsFor(child).single()
      assertEquals(listOf("LibChildGraph", "AppGraph"), childContext.chain.map { it.name })
      val childQuery = checkNotNull(index.queryContext(childContext))
      val accessors = index.graphComposition(childQuery).accessors
      val value = accessors.single { (it.pointer.element as? KtNamedDeclaration)?.name == "value" }
      val input = index.bindingsFor(value).single() as KaBinding.BoundInstance
      assertEquals(child.declarationId, input.ownerGraphId)
      val decorated = accessors.single {
        (it.pointer.element as? KtNamedDeclaration)?.name == "decorated"
      }
      assertEquals(
        "decorate",
        (index.bindingsFor(decorated).single().pointer.element as? KtNamedDeclaration)?.name,
      )
      val box = accessors.single { (it.pointer.element as? KtNamedDeclaration)?.name == "box" }
      assertEquals(
        "test.SourceBox<kotlin.String>",
        index.bindingsFor(box).single().typeKey.renderedType,
      )
      assertTrue(index.bindings.any { it.typeKey.renderedType == "libtest.LibInterfaceDependency" })

      val grandchild = index.graphs.single { it.name == "LibGrandchildGraph" }
      assertEquals(
        listOf("LibGrandchildGraph", "LibChildGraph", "AppGraph"),
        index.contextsFor(grandchild).single().chain.map { it.name },
      )
      for (graph in index.graphs) {
        val context = index.contextsFor(graph).single()
        val result =
          project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
        assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      }
    }
  }

  fun testSourceContributedFactoryFindsItsBinaryChild() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibSharedInputChild

          abstract class LocalScope

          @ContributesTo(LocalScope::class)
          interface ChildFactory : LibSharedInputChild.Factory

          @DependencyGraph(LocalScope::class)
          interface AppGraph
          """,
          fileName = "SourceFactoryBinaryChild.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val child = index.graphs.single { it.name == "LibSharedInputChild" }
      val context = index.contextsFor(child).single()
      assertEquals(listOf("LibSharedInputChild", "AppGraph"), context.chain.map { it.name })
      val query = checkNotNull(index.queryContext(context))
      val number = index.graphComposition(query).accessors.single()
      assertEquals(
        "number",
        (index.bindingsFor(number).single().pointer.element as? KtNamedDeclaration)?.name,
      )
      val result =
        project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testDirectBinaryChildInheritsSourceParentBindings() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibDirectChildGraph
          import libtest.LibParentService

          @DependencyGraph
          interface AppGraph {
            fun child(): LibDirectChildGraph
            @Provides fun parent(): LibParentService = object : LibParentService {}
          }
          """,
          fileName = "DirectBinaryChild.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val child = index.graphs.single { it.name == "LibDirectChildGraph" }
      val context = index.contextsFor(child).single()
      assertEquals(listOf("LibDirectChildGraph", "AppGraph"), context.chain.map { it.name })
      val query = checkNotNull(index.queryContext(context))
      val parent =
        index.graphComposition(query).accessors.single {
          it.key.renderedType == "libtest.LibParentService"
        }
      assertEquals(file.virtualFile, index.bindingsFor(parent, query).single().pointer.virtualFile)
      val result =
        project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testBinaryChildCompanionProvidersStayInTheirGraph() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibCompanionChildGraph
          import libtest.LibCompanionValue
          import libtest.LibParentService
          import libtest.LibSelfCompanionChildGraph

          @DependencyGraph
          interface AppGraph {
            fun child(): LibCompanionChildGraph
            @Provides fun parent(): LibParentService = object : LibParentService {}
          }

          @DependencyGraph
          interface OtherGraph {
            val other: LibCompanionValue
            fun selfChild(): LibSelfCompanionChildGraph
          }
          """,
          fileName = "BinaryCompanionProviders.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val child = index.graphs.single { it.name == "LibCompanionChildGraph" }
      val context = index.contextsFor(child).single()
      val query = checkNotNull(index.queryContext(context))
      val accessors = index.graphComposition(query).accessors
      assertEquals(2, accessors.size)
      val value = accessors.single { it.key.renderedType == "libtest.LibCompanionValue" }
      val provider = index.bindingsFor(value).single()
      assertEquals(child.declarationId, provider.ownerGraphId)
      assertEquals("provideValue", (provider.pointer.element as? KtNamedDeclaration)?.name)
      assertEquals(
        listOf("libtest.LibParentService"),
        provider.dependencies.map { it.typeKey.renderedType },
      )
      val enabled = accessors.single { it.key.renderedType == "kotlin.Boolean" }
      assertTrue(index.bindingsFor(enabled).single().dependencies.isEmpty())
      val other = index.consumerEntryAt(file.declarationsIncludingNested().property("other"))!!
      assertEquals(emptyList<KaBinding>(), index.resolveConsumer(other).uniformBindings)
      val selfChild = index.graphs.single { it.name == "LibSelfCompanionChildGraph" }
      val selfQuery = checkNotNull(index.queryContext(index.contextsFor(selfChild).single()))
      val selfValue = index.graphComposition(selfQuery).accessors.single()
      assertTrue(index.bindingsFor(selfValue, selfQuery).isEmpty())
      val result =
        project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testBinaryExtensionExclusionsAndReplacementsRemoveParentEdges() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibChildGraph
          import libtest.LibParentScope

          @DependencyGraph(LibParentScope::class, excludes = [LibChildGraph::class])
          interface ExcludedGraph {
            val excludedValue: String
          }

          @DependencyGraph(LibParentScope::class)
          interface ReplacedGraph {
            val replacedValue: String
          }
          """,
          fileName = "RemovedBinaryChildren.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      val initialChild = initial.graphs.single { it.name == "LibChildGraph" }
      assertEquals(
        listOf("LibChildGraph", "ReplacedGraph"),
        initial.contextsFor(initialChild).single().chain.map { it.name },
      )
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(
          document.textLength,
          "\n" +
            """
            @ContributesTo(LibParentScope::class, replaces = [LibChildGraph.Factory::class])
            interface Replacement
            """
              .trimIndent(),
        )
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val index = service.awaitIndex(file)
      val child = index.graphs.single { it.name == "LibChildGraph" }
      assertTrue(index.contextsFor(child).all { it.chain.size == 1 })
      for (name in listOf("excludedValue", "replacedValue")) {
        val consumer = index.consumerEntryAt(file.declarationsIncludingNested().property(name))!!
        assertEquals(emptyList<KaBinding>(), index.resolveConsumer(consumer).uniformBindings)
      }
      val factory =
        index.contributions.single {
          it.classId?.asFqNameString() == "libtest.LibChildGraph.Factory"
        }
      assertEquals(child.classId, factory.graphExtension?.classId)
    }
  }

  fun testInheritedSourceContributionEditsRefreshBinaryChildMembers() {
    module.withMetroLibFixtureLibrary {
      val inheritedFile =
        myFixture.addFileToProject(
          "test/ChildBase.kt",
          """
          package test

          import libtest.LibRegistry

          interface ChildBase {
            val contributed: LibRegistry
          }
          """
            .trimIndent(),
        ) as KtFile
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibChildGraph
          import libtest.LibChildScope

          @ContributesTo(LibChildScope::class)
          interface ChildMembers : ChildBase

          @DependencyGraph
          interface AppGraph {
            val factory: LibChildGraph.Factory
          }
          """,
          fileName = "InheritedSourceChildMembers.kt",
        )
      val service = project.service<MetroResolutionService>()
      fun contributedBinding(index: BindingIndex): KaBinding {
        val graph = index.graphs.single { it.name == "LibChildGraph" }
        val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
        val accessor =
          index.graphComposition(query).accessors.single {
            (it.pointer.element as? KtNamedDeclaration)?.name == "contributed"
          }
        return index.bindingsFor(accessor).single()
      }
      val initial = service.awaitIndex(file)
      assertEquals("libtest.LibRegistry", contributedBinding(initial).typeKey.renderedType)
      val document =
        checkNotNull(PsiDocumentManager.getInstance(project).getDocument(inheritedFile))
      WriteCommandAction.runWriteCommandAction(project) {
        document.setText(document.text.replace("LibRegistry", "LibInterfaceClient"))
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val updated = service.awaitIndex(file)
      assertEquals("libtest.LibInterfaceClient", contributedBinding(updated).typeKey.renderedType)
    }
  }

  fun testBinaryFactoryMergePreservesEverySourceInputOwner() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibSharedInputFactory
          import libtest.LibSharedInputScope

          @DependencyGraph
          interface FirstGraph {
            val first: Int
            @DependencyGraph.Factory
            interface Factory : LibSharedInputFactory<FirstGraph>
          }

          @DependencyGraph
          interface SecondGraph {
            val second: Int
            @DependencyGraph.Factory
            interface Factory : LibSharedInputFactory<SecondGraph>
          }

          @DependencyGraph(LibSharedInputScope::class)
          interface ParentGraph
          """,
          fileName = "SharedSourceAndBinaryInputs.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val owners = index.graphs.filter { it.name != "ParentGraph" }.map { it.declarationId }.toSet()
      assertEquals(3, owners.size)
      val instance =
        index.bindings.filterIsInstance<KaBinding.BoundInstance>().single {
          it.isBindingContainerInput && it.typeKey.renderedType == "libtest.LibFactoryExtras"
        }
      assertEquals(owners, setOfNotNull(instance.ownerGraphId) + instance.additionalOwnerGraphIds)
      for (graph in index.graphs) {
        val context = index.contextsFor(graph).single()
        val result =
          project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
        assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      }
    }
  }

  fun testBinaryGenericAssistedFactoriesKeepConcreteTargetsAndGraphDependencies() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibGenericAssistedDifferent
          import libtest.LibGenericAssistedExample
          import libtest.LibInheritedGenericAssistedFactory
          import libtest.LibQualifiedGenericAssisted
          import libtest.LibWrappedGenericAssisted

          @DependencyGraph
          interface AppGraph {
            val first: LibGenericAssistedExample.Factory<Int>
            val second: LibGenericAssistedExample.Factory2
            val third: LibGenericAssistedDifferent.Factory<Int, String>
            val fourth: LibGenericAssistedDifferent.Factory2<String>
            val inherited: LibInheritedGenericAssistedFactory<Int>
            val qualified: LibQualifiedGenericAssisted.Factory<Int>
            val wrapped: LibWrappedGenericAssisted.Factory<Int>
          }
          """,
          fileName = "BinaryGenericFactories.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val factories = index.bindings.filterIsInstance<KaBinding.AssistedFactory>()

      fun factory(type: String): KaBinding.AssistedFactory = factories.single {
        it.typeKey.renderedType == type
      }

      val first = factory("libtest.LibGenericAssistedExample.Factory<kotlin.Int>")
      assertEquals(
        "libtest.LibGenericAssistedExample<kotlin.Int>",
        first.targetTypeKey?.renderedType,
      )
      assertEquals(listOf("kotlin.Int"), first.dependencies.map { it.typeKey.renderedType })

      val second = factory("libtest.LibGenericAssistedExample.Factory2")
      assertEquals(
        "libtest.LibGenericAssistedExample<kotlin.Int>",
        second.targetTypeKey?.renderedType,
      )
      assertEquals(listOf("kotlin.Int"), second.dependencies.map { it.typeKey.renderedType })

      val third = factory("libtest.LibGenericAssistedDifferent.Factory<kotlin.Int, kotlin.String>")
      assertEquals(
        "libtest.LibGenericAssistedDifferent<kotlin.Int, kotlin.String>",
        third.targetTypeKey?.renderedType,
      )
      assertEquals(listOf("kotlin.String"), third.dependencies.map { it.typeKey.renderedType })

      val fourth = factory("libtest.LibGenericAssistedDifferent.Factory2<kotlin.String>")
      assertEquals(
        "libtest.LibGenericAssistedDifferent<kotlin.Int, kotlin.String>",
        fourth.targetTypeKey?.renderedType,
      )
      assertEquals(listOf("kotlin.String"), fourth.dependencies.map { it.typeKey.renderedType })

      val inherited = factory("libtest.LibInheritedGenericAssistedFactory<kotlin.Int>")
      assertEquals(
        "libtest.LibGenericAssistedExample<kotlin.Int>",
        inherited.targetTypeKey?.renderedType,
      )
      assertEquals(listOf("kotlin.Int"), inherited.dependencies.map { it.typeKey.renderedType })

      val qualified = factory("libtest.LibQualifiedGenericAssisted.Factory<kotlin.Int>")
      val qualifiedDependency = qualified.dependencies.single().typeKey
      assertEquals("kotlin.Int", qualifiedDependency.renderedType)
      assertTrue(qualifiedDependency.qualifier?.render(short = true)?.contains("primary") == true)

      val wrapped = factory("libtest.LibWrappedGenericAssisted.Factory<kotlin.Int>")
      val wrappedDependency = wrapped.dependencies.single()
      assertEquals("kotlin.Int", wrappedDependency.typeKey.renderedType)
      assertTrue(wrappedDependency.wrappedType is WrappedType.Provider)
      assertTrue(wrappedDependency.isDeferrable)
    }
  }

  fun testWrongQualifiedBinaryFactoryRemainsMissingButRetainsItsActualMetadata() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibEndpoint
          import libtest.LibGenericAssistedExample

          @DependencyGraph
          interface AppGraph {
            @LibEndpoint("selected")
            val factory: LibGenericAssistedExample.Factory<Int>
          }
          """,
          fileName = "WrongQualifiedBinaryFactory.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val accessor = file.declarationsIncludingNested().property("factory")
      val consumer = checkNotNull(index.consumerEntryAt(accessor))

      assertTrue(index.bindingsFor(consumer).isEmpty())
      val actualFactory =
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == "libtest.LibGenericAssistedExample.Factory<kotlin.Int>"
        }
      assertNull(actualFactory.typeKey.qualifier)
    }
  }

  fun testQualifiedLibraryInjectClassesResolveOnDemand() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibConsumer.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibEndpoint
          import libtest.LibQualifiedClient

          @Inject class LibConsumer(@LibEndpoint("primary") val client: LibQualifiedClient)
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      val bindings = index.bindingsFor(clientParam)
      assertEquals(listOf("injected class"), bindings.map { it.label })
      assertEquals(
        "LibQualifiedClient",
        (bindings.single().pointer.element as? KtNamedDeclaration)?.name,
      )
    }
  }

  fun testQualifiedLibraryInjectClassesDoNotSatisfyUnqualifiedConsumers() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibConsumer.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibQualifiedClient

          @Inject class LibConsumer(val client: LibQualifiedClient)
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
      assertTrue(index.bindingsFor(clientParam).isEmpty())
    }
  }

  fun testChangingLibraryRootsInvalidatesTheExistingSnapshot() {
    val file =
      myFixture.configureMetroFile(
        """
        import libtest.LibHttpClient

        @Inject class LibConsumer(val client: LibHttpClient)
        """,
        fileName = "LibConsumer.kt",
      )
    val service = project.service<MetroResolutionService>()
    val withoutLibrary = service.awaitIndex(file)

    module.withMetroLibFixtureLibrary {
      val withLibrary = service.awaitIndex(file)
      val declarations = file.declarationsIncludingNested()
      val client = withLibrary.consumerEntryAt(declarations.parameter("client"))!!

      assertNotSame(withoutLibrary, withLibrary)
      assertEquals(
        "libtest.LibHttpClient",
        withLibrary.bindingsFor(client).single().typeKey.renderedType,
      )
    }

    assertNotSame(withoutLibrary, service.awaitIndex(file))
  }

  fun testUnchangedLibraryInputsReuseBinaryDeclarationsAfterSourceEdits() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibHttpClient

          @Inject class LibConsumer(val client: LibHttpClient)
          """,
          fileName = "LibConsumer.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      val initialLibraryBinding =
        initial.bindings.single { it.typeKey.renderedType == "libtest.LibHttpClient" }

      myFixture.editor.caretModel.moveToOffset(file.textLength)
      myFixture.type("\n")
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(file)
      val updatedLibraryBinding =
        updated.bindings.single { it.typeKey.renderedType == "libtest.LibHttpClient" }
      assertNotSame(initial, updated)
      assertSame(initialLibraryBinding, updatedLibraryBinding)
    }
  }

  fun testFactoryDependencyFileEditsRefreshItsBinaryDependencyOverlay() {
    module.withMetroLibFixtureLibrary {
      val factoryFile =
        myFixture.addFileToProject(
          "test/StableFactory.kt",
          """
          package test

          import dev.zacsweers.metro.*

          @AssistedInject
          class StableExample<T>(@Assisted val id: String, val dependency: T) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(id: String): StableExample<T>
            }
          }

          @Inject class UnrelatedDependency
          """
            .trimIndent(),
        ) as KtFile
      val graphFile =
        myFixture.configureMetroFile(
          """
          import libtest.LibClientWithDeps

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: StableExample.Factory<LibClientWithDeps>
          }
          """,
          fileName = "StableFactoryGraph.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(graphFile)
      val initialFactory =
        initial.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == "test.StableExample.Factory<libtest.LibClientWithDeps>"
        }
      val initialClient =
        initial.bindings.single { it.typeKey.renderedType == "libtest.LibClientWithDeps" }
      val initialHttpClient =
        initial.bindings.single { it.typeKey.renderedType == "libtest.LibHttpClient" }

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(factoryFile))
      val previousName = "UnrelatedDependency"
      val nameOffset = document.text.indexOf(previousName)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(nameOffset, nameOffset + previousName.length, "RenamedDependency")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(graphFile)
      val updatedFactory =
        updated.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == "test.StableExample.Factory<libtest.LibClientWithDeps>"
        }
      assertNotSame(initial, updated)
      assertNotSame(initialFactory, updatedFactory)
      assertEquals(
        initialFactory.targetConstructorDependencies,
        updatedFactory.targetConstructorDependencies,
      )
      // Class discovery reads this source file, so its new stamp refreshes the derived overlay.
      assertNotSame(
        initialClient,
        updated.bindings.single { it.typeKey.renderedType == "libtest.LibClientWithDeps" },
      )
      assertNotSame(
        initialHttpClient,
        updated.bindings.single { it.typeKey.renderedType == "libtest.LibHttpClient" },
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "test.RenamedDependency" })
    }
  }

  fun testSourceFactoryChainsKeepOnlyLinearShardBindings() {
    val factoryCount = 16
    repeat(factoryCount) { number ->
      val nextParameter =
        if (number + 1 < factoryCount) ", val next: Chain${number + 1}.Factory" else ""
      myFixture.addFileToProject(
        "test/Chain$number.kt",
        """
        package test

        import dev.zacsweers.metro.*

        @AssistedInject
        class Chain$number(@Assisted val id: String$nextParameter) {
          @AssistedFactory
          fun interface Factory {
            fun create(id: String): Chain$number
          }
        }
        """
          .trimIndent(),
      )
    }
    val graphFile =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val factory: Chain0.Factory
        }
        """,
        fileName = "FactoryChainGraph.kt",
      )
    val settings = MetroSettings.getInstance(project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val index = project.service<MetroResolutionService>().awaitIndex(graphFile)
      val factories = index.bindings.filterIsInstance<KaBinding.AssistedFactory>()

      assertEquals(factoryCount, factories.map { it.typeKey }.distinct().size)
      // A shard can contain its own factory and its directly requested neighbor. It must not
      // retain a second copy of the entire remaining chain.
      assertTrue(
        "Expected at most ${factoryCount * 2} factory bindings, got ${factories.size}",
        factories.size <= factoryCount * 2,
      )
      val context = index.contextsFor(index.graphs.single()).single()
      val queryContext = checkNotNull(index.queryContext(context))
      assertEquals(
        factoryCount,
        index.bindingsInContext(queryContext).filterIsInstance<KaBinding.AssistedFactory>().size,
      )
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testNestedSourceFactoryEditsRefreshTheirBinaryDependencyOverlay() {
    module.withMetroLibFixtureLibrary {
      val innerFile =
        myFixture.addFileToProject(
          "test/CacheInner.kt",
          """
          package test

          import dev.zacsweers.metro.*
          import libtest.LibRetargetedDependencyA
          import libtest.LibRetargetedDependencyB

          @AssistedInject
          class CacheInner<T>(
            @Assisted val input: T,
            val dependency: LibRetargetedDependencyA,
          ) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(input: T): CacheInner<T>
            }
          }
          """
            .trimIndent(),
        ) as KtFile
      myFixture.addFileToProject(
        "test/CacheOuter.kt",
        """
        package test

        import dev.zacsweers.metro.*

        @AssistedInject
        class CacheOuter<T>(@Assisted val id: String, val inner: CacheInner.Factory<T>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): CacheOuter<T>
          }
        }
        """
          .trimIndent(),
      )
      val graphFile =
        myFixture.configureMetroFile(
          """
          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: CacheOuter.Factory<Int>
          }
          """,
          fileName = "NestedFactoryCacheGraph.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(graphFile)
      val innerKey = "test.CacheInner.Factory<kotlin.Int>"
      val initialInner =
        initial.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == innerKey
        }
      val initialDependency =
        initial.bindings.single { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      assertEquals(
        listOf("libtest.LibRetargetedDependencyA"),
        initialInner.targetConstructorDependencies.map { it.typeKey.renderedType },
      )

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(innerFile))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val unchanged = service.awaitIndex(graphFile)
      assertNotSame(initial, unchanged)
      val refreshedInner =
        unchanged.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == innerKey
        }
      assertNotSame(initialInner, refreshedInner)
      assertEquals(
        initialInner.targetConstructorDependencies,
        refreshedInner.targetConstructorDependencies,
      )
      assertNotSame(
        initialDependency,
        unchanged.bindings.single {
          it.typeKey.renderedType == "libtest.LibRetargetedDependencyA"
        },
      )

      val oldDependency = "dependency: LibRetargetedDependencyA"
      val dependencyOffset = document.text.indexOf(oldDependency)
      assertTrue(dependencyOffset >= 0)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(
          dependencyOffset,
          dependencyOffset + oldDependency.length,
          "dependency: LibRetargetedDependencyB",
        )
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(graphFile)
      val updatedInner =
        updated.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == innerKey
        }
      assertNotSame(initialInner, updatedInner)
      assertEquals(
        listOf("libtest.LibRetargetedDependencyB"),
        updatedInner.targetConstructorDependencies.map { it.typeKey.renderedType },
      )
      assertFalse(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertTrue(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testBinaryToSourceFactoryCacheTracksDefaultedDependencies() {
    module.withMetroLibFixtureLibrary {
      val factoryFile =
        myFixture.addFileToProject(
          "test/DefaultedFactory.kt",
          """
          package test

          import dev.zacsweers.metro.*

          class Missing

          @AssistedInject
          class DefaultedExample<T>(
            @Assisted val input: T,
            val dependency: Missing = Missing(),
          ) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(input: T): DefaultedExample<T>
            }
          }
          """
            .trimIndent(),
        ) as KtFile
      val graphFile =
        myFixture.configureMetroFile(
          """
          import libtest.LibGenericAssistedExample

          @DependencyGraph
          interface AppGraph {
            val factory: LibGenericAssistedExample.Factory<DefaultedExample.Factory<Int>>
          }
          """,
          fileName = "BinaryToSourceFactoryGraph.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(graphFile)
      val factoryKey = "test.DefaultedExample.Factory<kotlin.Int>"
      val initialFactory =
        initial.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == factoryKey
        }
      assertTrue(initialFactory.targetConstructorDependencies.single().hasDefault)

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(factoryFile))
      val defaultValue = " = Missing()"
      val defaultOffset = document.text.indexOf(defaultValue)
      assertTrue(defaultOffset >= 0)
      WriteCommandAction.runWriteCommandAction(project) {
        document.deleteString(defaultOffset, defaultOffset + defaultValue.length)
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(graphFile)
      val updatedFactory =
        updated.bindings.filterIsInstance<KaBinding.AssistedFactory>().single {
          it.typeKey.renderedType == factoryKey
        }
      assertNotSame(initialFactory, updatedFactory)
      assertFalse(updatedFactory.targetConstructorDependencies.single().hasDefault)
    }
  }

  fun testRepeatedSourceGenericFactoryRequestsShareTheirLibraryUseSites() {
    module.withMetroLibFixtureLibrary {
      myFixture.addFileToProject(
        "test/SharedFactory.kt",
        """
        package test

        import dev.zacsweers.metro.*

        @AssistedInject
        class SharedExample<T>(@Assisted val id: String, val dependency: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): SharedExample<T>
          }
        }
        """
          .trimIndent(),
      )
      repeat(8) { index ->
        myFixture.addFileToProject(
          "test/Consumer$index.kt",
          """
          package test

          import dev.zacsweers.metro.Inject
          import libtest.LibClientWithDeps

          @Inject class Consumer$index(val factory: SharedExample.Factory<LibClientWithDeps>)
          """
            .trimIndent(),
        )
      }
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibClientWithDeps

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: SharedExample.Factory<LibClientWithDeps>
            val consumer: Consumer0
          }
          """,
          fileName = "SharedFactoryGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val specializedFactories =
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().filter {
          it.typeKey.renderedType == "test.SharedExample.Factory<libtest.LibClientWithDeps>"
        }
      assertTrue(
        "Separate consumer shards should share one factory declaration",
        specializedFactories.size > 1,
      )

      val useSites = allowAnalysisOnEdt {
        sourceAssistedFactoryUseSites(
          project,
          index.bindings,
          index.consumers,
          ConsumerOwnershipBundle.build(index),
        )
      }
      val sharedUseSites = checkNotNull(useSites[specializedFactories.first()])
      assertEquals(1, sharedUseSites.size)
      for (factory in specializedFactories) {
        assertSame(sharedUseSites, useSites[factory])
      }

      val accessor = file.declarationsIncludingNested().property("factory")
      val consumer = checkNotNull(index.consumerEntryAt(accessor))
      assertEquals(
        1,
        index.bindingsFor(consumer).filterIsInstance<KaBinding.AssistedFactory>().size,
      )
      assertTrue(index.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(index.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testRetargetingSourceAssistedFactoryRefreshesBinaryDependencyOverlay() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRetargetedWidgetA
          import libtest.LibRetargetedWidgetB

          @AssistedFactory
          interface WidgetFactory {
            fun create(id: String): LibRetargetedWidgetA
          }

          @DependencyGraph
          interface AppGraph {
            val widgetFactory: WidgetFactory
          }
          """,
          fileName = "RetargetedFactory.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      assertTrue(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertFalse(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      val oldTarget = "): LibRetargetedWidgetA"
      val targetOffset = document.text.indexOf(oldTarget)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(
          targetOffset,
          targetOffset + oldTarget.length,
          "): LibRetargetedWidgetB",
        )
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(file)
      assertNotSame(initial, updated)
      assertFalse(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertTrue(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
    }
  }

  fun testChangingGeneratedProviderConstructorDependencyRefreshesBinaryOverlay() {
    project.setMetroOptions("generate-contribution-providers" to "true")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRetargetedDependencyA
          import libtest.LibRetargetedDependencyB

          interface Service

          @Inject
          @ContributesBinding(AppScope::class)
          class ServiceImpl(val dependency: LibRetargetedDependencyA) : Service

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val service: Service
          }
          """,
          fileName = "GeneratedProvider.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      assertTrue(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertFalse(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      val oldDependency = "dependency: LibRetargetedDependencyA"
      val dependencyOffset = document.text.indexOf(oldDependency)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(
          dependencyOffset,
          dependencyOffset + oldDependency.length,
          "dependency: LibRetargetedDependencyB",
        )
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(file)
      assertNotSame(initial, updated)
      assertFalse(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertTrue(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
    }
  }

  fun testContributedInterfaceAccessorEditsRefreshBinaryOverlay() {
    module.withMetroLibFixtureLibrary {
      val accessors =
        myFixture.addFileToProject(
          "test/LibraryAccessors.kt",
          """
          package test

          import dev.zacsweers.metro.ContributesTo
          import libtest.LibRetargetedDependencyA
          import libtest.LibRetargetedDependencyB

          object AccessorScope

          @ContributesTo(AccessorScope::class)
          interface LibraryAccessors {
            val dependency: LibRetargetedDependencyA
          }
          """
            .trimIndent(),
        ) as KtFile
      val file =
        myFixture.configureMetroFile(
          """
          @DependencyGraph(AccessorScope::class)
          interface AppGraph
          """,
          fileName = "ContributedAccessorGraph.kt",
        )
      val service = project.service<MetroResolutionService>()
      val initial = service.awaitIndex(file)
      val initialGraph = initial.graphs.single { it.name == "AppGraph" }
      val initialQuery =
        checkNotNull(initial.queryContext(initial.contextsFor(initialGraph).single()))
      assertEquals(
        listOf("libtest.LibRetargetedDependencyA"),
        initial.graphComposition(initialQuery).accessors.map { it.key.renderedType },
      )
      val initialDependency =
        initial.bindings.single { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      assertFalse(
        initial.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )
      assertFalse(initial.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })

      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(accessors))
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      val whitespaceOnly = service.awaitIndex(file)
      assertSame(
        initialDependency,
        whitespaceOnly.bindings.single {
          it.typeKey.renderedType == "libtest.LibRetargetedDependencyA"
        },
      )

      val oldType = "dependency: LibRetargetedDependencyA"
      val typeOffset = document.text.indexOf(oldType)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(
          typeOffset,
          typeOffset + oldType.length,
          "dependency: LibRetargetedDependencyB",
        )
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()

      val updated = service.awaitIndex(file)
      assertNotSame(initial, updated)
      assertFalse(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyA" }
      )
      assertTrue(
        updated.bindings.any { it.typeKey.renderedType == "libtest.LibRetargetedDependencyB" }
      )
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(updated.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })
      val updatedGraph = updated.graphs.single { it.name == "AppGraph" }
      val updatedQuery =
        checkNotNull(updated.queryContext(updated.contextsFor(updatedGraph).single()))
      assertEquals(
        listOf("libtest.LibRetargetedDependencyB"),
        updated.graphComposition(updatedQuery).accessors.map { it.key.renderedType },
      )
    }
  }

  fun testWrittenDefaultAccessorEditsRefreshBinaryOverlay() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRetargetedDependencyA
          import libtest.LibRetargetedDependencyB

          interface LibraryAccessors<T> {
            val dependency: T
          }

          @DependencyGraph(AppScope::class)
          interface AppGraph : LibraryAccessors<LibRetargetedDependencyB> {
            val stable: LibRetargetedDependencyA
            // inherited dependency
          }
          """,
          fileName = "WrittenDefaultAccessorGraph.kt",
        )
      assertDefaultAccessorEditsRefreshBinaryOverlay(file, file)
    }
  }

  fun testContributedDefaultAccessorEditsRefreshBinaryOverlay() {
    module.withMetroLibFixtureLibrary {
      val accessors =
        myFixture.addFileToProject(
          "test/DefaultAccessors.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.ContributesTo
          import libtest.LibRetargetedDependencyB

          interface LibraryAccessors<T> {
            val dependency: T
          }

          @ContributesTo(AppScope::class)
          interface DefaultAccessors : LibraryAccessors<LibRetargetedDependencyB> {
            // inherited dependency
          }
          """
            .trimIndent(),
        ) as KtFile
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRetargetedDependencyA

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val stable: LibRetargetedDependencyA
          }
          """,
          fileName = "ContributedDefaultAccessorGraph.kt",
        )
      assertDefaultAccessorEditsRefreshBinaryOverlay(file, accessors)
    }
  }

  private fun assertDefaultAccessorEditsRefreshBinaryOverlay(
    graphFile: KtFile,
    implementationOwner: KtFile,
  ) {
    val service = project.service<MetroResolutionService>()
    val document =
      checkNotNull(PsiDocumentManager.getInstance(project).getDocument(implementationOwner))
    val inheritedMember = "// inherited dependency"
    val concreteMember =
      "override val dependency: LibRetargetedDependencyB get() = error(\"fixture\")"
    val stableType = "libtest.LibRetargetedDependencyA"
    val inheritedType = "libtest.LibRetargetedDependencyB"

    fun roots(index: BindingIndex): List<String> {
      val graph = index.graphs.single { it.name == "AppGraph" }
      val query = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
      return index.accessorsFor(query).map { it.key.renderedType }.sorted()
    }

    fun replaceMember(before: String, after: String) {
      val offset = document.text.indexOf(before)
      check(offset >= 0)
      WriteCommandAction.runWriteCommandAction(project) {
        document.replaceString(offset, offset + before.length, after)
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    fun addWhitespace() {
      WriteCommandAction.runWriteCommandAction(project) {
        document.insertString(document.textLength, "\n")
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    val initial = service.awaitIndex(graphFile)
    assertEquals(listOf(stableType, inheritedType), roots(initial))
    val initialDependency = initial.bindings.single { it.typeKey.renderedType == inheritedType }
    assertTrue(initial.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
    assertTrue(initial.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })

    addWhitespace()
    val whitespaceOnly = service.awaitIndex(graphFile)
    assertEquals(listOf(stableType, inheritedType), roots(whitespaceOnly))
    assertSame(
      initialDependency,
      whitespaceOnly.bindings.single { it.typeKey.renderedType == inheritedType },
    )

    // The abstract declaration and its type stay unchanged. Only its concrete override changes
    // whether the graph requests the library type, so the override metadata must invalidate the
    // shared source summary as well as the graph's accessor list.
    replaceMember(inheritedMember, concreteMember)
    val concrete = service.awaitIndex(graphFile)
    assertEquals(listOf(stableType), roots(concrete))
    assertFalse(concrete.bindings.any { it.typeKey.renderedType == inheritedType })
    // The fixture's AppScope-contributed service still requests this shared dependency chain.
    assertTrue(concrete.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
    assertTrue(concrete.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })
    val concreteStable = concrete.bindings.single { it.typeKey.renderedType == stableType }

    addWhitespace()
    val concreteWhitespace = service.awaitIndex(graphFile)
    assertEquals(listOf(stableType), roots(concreteWhitespace))
    assertSame(
      concreteStable,
      concreteWhitespace.bindings.single { it.typeKey.renderedType == stableType },
    )

    replaceMember(concreteMember, inheritedMember)
    val restored = service.awaitIndex(graphFile)
    assertEquals(listOf(stableType, inheritedType), roots(restored))
    assertTrue(restored.bindings.any { it.typeKey.renderedType == inheritedType })
    assertTrue(restored.bindings.any { it.typeKey.renderedType == "libtest.LibClientWithDeps" })
    assertTrue(restored.bindings.any { it.typeKey.renderedType == "libtest.LibHttpClient" })
  }

  fun testLibraryGeneratedContributionAliasesPreserveAnvilRanks() {
    project.setMetroOptions(
      "custom-contributes-binding" to "libtest/LibRankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRankedService

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val service: LibRankedService
          }
          """,
          fileName = "RankedLibraryGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val service = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!
      val matching = index.resolveConsumer(service).uniformBindings.orEmpty()

      assertEquals(
        listOf("bindLibRankedService"),
        matching.map { (it.pointer.element as? KtNamedDeclaration)?.name },
      )
      assertEquals(listOf(100), matching.map { it.priority })
      assertTrue(matching.single().priorityFromAnvilRank)
      assertEquals(
        listOf("libtest.LibHigherRankedService"),
        matching.map { it.originClassId?.asFqNameString() },
      )
    }
  }

  fun testLibraryGeneratedContributionAliasesPreservePrioritiesWithoutInterop() {
    project.setMetroOptions("custom-contributes-binding" to "libtest/LibPrioritizedBinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRankedService

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val service: LibRankedService
          }
          """,
          fileName = "PrioritizedLibraryGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val service = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!
      val matching = index.resolveConsumer(service).uniformBindings.orEmpty()

      assertEquals(
        listOf("libtest.LibHigherRankedService"),
        matching.map {
          it.originClassId?.asFqNameString()
        },
      )
      assertEquals(listOf(100), matching.map { it.priority })
      assertFalse(matching.single().priorityFromAnvilRank)
    }
  }

  fun testLibraryContributionProviderRecoversPriorityFromOrigin() {
    project.setMetroOptions("custom-contributes-binding" to "libtest/LibPrioritizedBinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibContained
          import libtest.LibPrioritizedBinding

          @Inject @LibPrioritizedBinding(AppScope::class, priority = 100)
          class HigherContained : LibContained

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val service: LibContained
          }
          """,
          fileName = "PrioritizedOriginGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val service = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

      assertEquals(
        listOf("HigherContained"),
        index.resolveConsumer(service).uniformBindings.orEmpty().map { it.implementationName },
      )
    }
  }

  fun testLibraryMapContributionsPreservePrioritiesWithoutInterop() {
    project.setMetroOptions("custom-into-map" to "libtest/LibPrioritizedMapBinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibRankedService

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val services: Map<String, LibRankedService>
          }
          """,
          fileName = "PrioritizedLibraryMapGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val services =
        index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!
      val matching = index.resolveConsumer(services).uniformBindings.orEmpty()

      assertEquals(
        listOf("libtest.LibHigherRankedService"),
        matching.map { it.originClassId?.asFqNameString() },
      )
      assertEquals(listOf(100), matching.map { it.priority })
      assertFalse(matching.single().priorityFromAnvilRank)
    }
  }

  fun testLibrarySetContributionAliasesStayAdditive() {
    project.setMetroOptions("custom-contributes-into-set" to "libtest/LibCustomMultibinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibSetService
          import libtest.LibMultibindingScope

          @DependencyGraph(LibMultibindingScope::class)
          interface AppGraph {
            val services: Set<LibSetService>
          }
          """,
          fileName = "LibrarySetGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val services =
        index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!
      val matching = index.resolveConsumer(services).uniformBindings.orEmpty()

      assertEquals(
        listOf("bindLibSetService", "bindLibSetService"),
        matching.map { (it.pointer.element as? KtNamedDeclaration)?.name },
      )
      assertEquals(
        setOf("libtest.LibFirstSetService", "libtest.LibSecondSetService"),
        matching.mapTo(mutableSetOf()) { it.originClassId?.asFqNameString() },
      )
      assertEquals(setOf(Int.MIN_VALUE), matching.mapTo(mutableSetOf()) { it.priority })
      assertTrue(matching.none { it.priorityFromAnvilRank })
    }
  }

  fun testLibrarySetContributionProviderStaysAdditive() {
    project.setMetroOptions("custom-contributes-into-set" to "libtest/LibCustomMultibinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibCustomMultibinding
          import libtest.LibContainedSetService
          import libtest.LibMultibindingScope

          @Inject @LibCustomMultibinding(LibMultibindingScope::class)
          class AdditionalContained : LibContainedSetService

          @DependencyGraph(LibMultibindingScope::class)
          interface AppGraph {
            val services: Set<LibContainedSetService>
          }
          """,
          fileName = "SetOriginGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val services =
        index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!

      assertEquals(
        setOf("LibContainedSetImpl", "AdditionalContained"),
        index.resolveConsumer(services).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
          it.implementationName
        },
      )
    }
  }

  fun testLibraryKeyedCustomMultibindingAliasesRecoverMapPriority() {
    project.setMetroOptions("custom-contributes-into-set" to "libtest/LibCustomMultibinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibPrioritizedCustomMapService
          import libtest.LibMultibindingScope

          @DependencyGraph(LibMultibindingScope::class)
          interface AppGraph {
            val services: Map<String, LibPrioritizedCustomMapService>
          }
          """,
          fileName = "PrioritizedLibraryCustomMapGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val services =
        index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!
      val matching = index.resolveConsumer(services).uniformBindings.orEmpty()

      assertEquals(
        listOf("bindLibPrioritizedCustomMapService"),
        matching.map { (it.pointer.element as? KtNamedDeclaration)?.name },
      )
      assertEquals(
        listOf("libtest.LibHigherPriorityCustomMapService"),
        matching.map { it.originClassId?.asFqNameString() },
      )
      assertEquals(listOf(100), matching.map { it.priority })
      assertTrue(matching.single().mapKeyValue?.contains("shared") == true)
    }
  }

  fun testLibraryMixedBindingPriorityAndSetAliasesStayIndependent() {
    project.setMetroOptions("custom-contributes-binding" to "libtest/LibMixedMultibindingBinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibMixedMultibindingService
          import libtest.LibMultibindingScope

          @DependencyGraph(LibMultibindingScope::class)
          interface AppGraph {
            val service: LibMixedMultibindingService
            val services: Set<LibMixedMultibindingService>
          }
          """,
          fileName = "MixedLibrarySetGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()
      val service = index.consumerEntryAt(declarations.property("service"))!!
      val services = index.consumerEntryAt(declarations.property("services"))!!
      val ordinary = index.resolveConsumer(service).uniformBindings.orEmpty()
      val collected = index.resolveConsumer(services).uniformBindings.orEmpty()

      assertEquals(
        listOf("libtest.LibMixedMultibindingServiceImpl"),
        ordinary.map { it.originClassId?.asFqNameString() },
      )
      assertEquals(listOf(5), ordinary.map { it.priority })
      assertEquals(
        setOf("libtest.LibMixedMultibindingServiceImpl", "libtest.LibOtherMixedSetService"),
        collected.mapTo(mutableSetOf()) { it.originClassId?.asFqNameString() },
      )
      assertEquals(setOf(Int.MIN_VALUE), collected.mapTo(mutableSetOf()) { it.priority })
    }
  }

  fun testLibrarySetContributionsHonorIgnoredImplementationQualifiers() {
    project.setMetroOptions("custom-contributes-into-set" to "libtest/LibCustomMultibinding")
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibIgnoreQualifierSetService
          import libtest.LibCustomMultibinding
          import libtest.LibMultibindingScope

          @Inject @LibCustomMultibinding(LibMultibindingScope::class)
          class AdditionalService : LibIgnoreQualifierSetService

          @DependencyGraph(LibMultibindingScope::class)
          interface AppGraph {
            val services: Set<LibIgnoreQualifierSetService>
          }
          """,
          fileName = "IgnoreQualifierLibrarySetGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val services =
        index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!
      val matching = index.resolveConsumer(services).uniformBindings.orEmpty()

      assertEquals(
        setOf("LibIgnoreQualifierSetServiceImpl", "AdditionalService"),
        matching.mapTo(mutableSetOf()) { it.implementationName },
      )
      assertEquals(setOf(Int.MIN_VALUE), matching.mapTo(mutableSetOf()) { it.priority })
    }
  }

  fun testLibraryQualifierDefaultsMatchExplicitValues() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
          import libtest.LibEndpoint

          interface Service

          @DependencyGraph
          interface AppGraph {
            @LibEndpoint(version = 1, name = "main") val service: Service
            @Provides @LibEndpoint fun provideService(): Service = object : Service {}
          }
          """,
          fileName = "BinaryQualifierGraph.kt",
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

      assertEquals(
        listOf("provideService"),
        index.resolveConsumer(accessor).uniformBindings.orEmpty().mapNotNull {
          (it.pointer.element as? KtNamedDeclaration)?.name
        },
      )
    }
  }

  fun testLibraryContributionsResolveViaHintFunctions() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibGraph.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibAnalytics
          import libtest.LibContained
          import libtest.LibExplicit
          import libtest.LibHidden
          import libtest.LibService

          @DependencyGraph(AppScope::class)
          interface LibGraph {
            val service: LibService
            val analytics: Set<LibAnalytics>
            val explicit: LibExplicit
            val contained: LibContained
            val hidden: LibHidden
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      val serviceAccessor = index.consumerEntryAt(declarations.property("service"))!!
      val serviceBindings = index.bindingsFor(serviceAccessor)
      assertEquals(listOf("contributed binding"), serviceBindings.map { it.label })
      assertEquals("LibServiceImpl", serviceBindings.single().implementationName)

      val analyticsAccessor = index.consumerEntryAt(declarations.property("analytics"))!!
      val analyticsBindings = index.bindingsFor(analyticsAccessor)
      assertEquals(
        listOf("multibinding contribution"),
        analyticsBindings.map { it.label },
      )
      assertEquals("LibAnalyticsImpl", analyticsBindings.single().implementationName)

      // Explicit binding<T>() bound types aren't recoverable from binary annotations; they
      // resolve through the generated nested MetroContribution @Binds members instead
      val explicitAccessor = index.consumerEntryAt(declarations.property("explicit"))!!
      val explicitBindings = index.bindingsFor(explicitAccessor)
      assertEquals(listOf("contributed binding"), explicitBindings.map { it.label })
      assertEquals("LibExplicitImpl", explicitBindings.single().implementationName)

      // Contribution-provider container objects expose their @Provides members, attributed to
      // the @Origin class
      val containedAccessor = index.consumerEntryAt(declarations.property("contained"))!!
      val containedBindings = index.bindingsFor(containedAccessor)
      assertEquals(listOf("provides"), containedBindings.map { it.label })
      assertEquals("LibContainedImpl", containedBindings.single().implementationName)

      // Internal hints from non-friend modules are filtered, mirroring the compiler
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      assertTrue(index.bindingsFor(hiddenAccessor).isEmpty())

      // Library contributions also appear in the graph's contribution list
      val graph = index.graphEntryAt(declarations.klass("LibGraph"))!!
      val contributionNames =
        index.contributionsForScopes(graph.scopeKeys).mapNotNull {
          (it.pointer.element as? KtNamedDeclaration)?.name
        }
      assertTrue(
        contributionNames.containsAll(
          listOf("LibServiceImpl", "LibAnalyticsImpl", "LibExplicitImpl", "LibContainedImpl")
        )
      )
      assertFalse("LibHiddenImpl" in contributionNames)
    }
  }

  fun testLibraryResolutionRespectsResolveFromLibrariesSetting() {
    val settings = MetroSettings.getInstance(project).state
    settings.resolveFromLibraries = false
    try {
      module.withMetroLibFixtureLibrary {
        val file =
          myFixture.configureByText(
            "LibConsumer.kt",
            """
            package test

            import dev.zacsweers.metro.Inject
            import libtest.LibHttpClient

            @Inject class LibConsumer(val client: LibHttpClient)
            """
              .trimIndent(),
          ) as KtFile
        val index = project.service<MetroResolutionService>().awaitIndex(file)
        val declarations = file.declarationsIncludingNested()
        val clientParam = index.consumerEntryAt(declarations.parameter("client"))!!
        assertTrue(index.bindingsFor(clientParam).isEmpty())
      }
    } finally {
      settings.resolveFromLibraries = true
    }
  }

  fun testCustomProviderAndLazyWrappersAreUnwrapped() {
    project.setMetroOptions(
      "custom-provider" to "test/CustomProvider",
      "custom-lazy" to "test/CustomLazy",
    )
    val file =
      myFixture.configureByText(
        "CustomWrappers.kt",
        """
        package test

        import dev.zacsweers.metro.Binds
        import dev.zacsweers.metro.Inject

        class CustomProvider<T>
        class CustomLazy<T>

        interface Service
        @Inject class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        @Inject
        class Consumer(
          val serviceProvider: CustomProvider<Service>,
          val serviceLazy: CustomLazy<Service>,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    for (name in listOf("serviceProvider", "serviceLazy")) {
      val consumer = index.consumerEntryAt(declarations.parameter(name))!!
      assertEquals("test.Service", consumer.key.renderedType)
      assertEquals(listOf("binds"), index.bindingsFor(consumer).map { it.label })
    }
  }

  fun testBindsOptionalOfExposesOptionalBinding() {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      """
      package dagger

      annotation class BindsOptionalOf
      """
        .trimIndent(),
    )
    // The light test fixture's mock JDK lacks java.util.Optional; stub it so it resolves.
    myFixture.addFileToProject(
      "java/util/Optional.kt",
      """
      package java.util

      class Optional<T>
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "Optionals.kt",
        """
        package test

        import dagger.BindsOptionalOf
        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import java.util.Optional

        interface Service

        @BindingContainer
        interface ServiceBindings {
          @BindsOptionalOf fun optionalService(): Service
          @BindsOptionalOf fun anotherOptionalService(): Service
        }

        @DependencyGraph(bindingContainers = [ServiceBindings::class])
        interface AppGraph {
          val service: Optional<Service>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // The @BindsOptionalOf declaration exposes an Optional<Service> binding.
    val optionalBinding = index.bindingEntriesAt(declarations.function("optionalService")).single()
    assertEquals("optional binding", optionalBinding.label)
    assertEquals("java.util.Optional<test.Service>", optionalBinding.typeKey.renderedType)
    val wrappedDependency = optionalBinding.dependencies.single()
    assertEquals("test.Service", wrappedDependency.typeKey.renderedType)
    assertTrue(wrappedDependency.hasDefault)

    val consumer = index.consumerEntryAt(declarations.property("service"))!!
    assertEquals("java.util.Optional<test.Service>", consumer.key.renderedType)
    assertEquals(
      listOf("optional binding", "optional binding"),
      index.bindingsFor(consumer).map { it.label },
    )
    val context = index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
    val queryContext = index.queryContext(context)!!
    assertEquals(listOf(optionalBinding), index.bindingsFor(consumer, queryContext))
  }

  fun testBindsOptionalOfIgnoredWithoutDaggerInterop() {
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      """
      package dagger

      annotation class BindsOptionalOf
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "OptionalsOff.kt",
        """
        package test

        import dagger.BindsOptionalOf
        import java.util.Optional

        interface Service

        interface ServiceBindings {
          @BindsOptionalOf fun optionalService(): Service
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    assertTrue(index.bindingEntriesAt(declarations.function("optionalService")).isEmpty())
  }

  fun testOptionalBindingMarksConsumersOptional() {
    val file =
      myFixture.configureByText(
        "OptionalMarker.kt",
        """
        package test

        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.OptionalBinding

        interface HttpClient

        @DependencyGraph
        interface AppGraph {
          @OptionalBinding val httpClient: HttpClient? get() = null
        }

        @Inject
        class Consumer(
          val flag: Boolean = false,
          val required: HttpClient,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // The @OptionalBinding accessor is a consumer (despite its default body) and is optional.
    val accessor = index.consumerEntryAt(declarations.property("httpClient"))!!
    assertTrue(accessor.isOptional)

    // Under DEFAULT behavior, a defaulted parameter is optional; a required one is not.
    assertTrue(index.consumerEntryAt(declarations.parameter("flag"))!!.isOptional)
    assertFalse(index.consumerEntryAt(declarations.parameter("required"))!!.isOptional)
  }

  fun testRequireOptionalBindingIgnoresBareDefaults() {
    project.setMetroOptions("optional-binding-behavior" to "REQUIRE_OPTIONAL_BINDING")
    val file =
      myFixture.configureByText(
        "RequireOptional.kt",
        """
        package test

        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.OptionalBinding

        interface HttpClient

        @Inject
        class Consumer(
          val bare: Boolean = false,
          @OptionalBinding val marked: HttpClient,
        )
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // A bare default no longer counts; only the explicit annotation does.
    assertFalse(index.consumerEntryAt(declarations.parameter("bare"))!!.isOptional)
    assertTrue(index.consumerEntryAt(declarations.parameter("marked"))!!.isOptional)
  }

  fun testFunctionTypesAreNotUnwrappedWhenFunctionProvidersAreDisabled() {
    project.setMetroOptions("enable-function-providers" to "false")
    val file =
      myFixture.configureByText(
        "FunctionProvider.kt",
        """
        package test

        import dev.zacsweers.metro.Binds
        import dev.zacsweers.metro.Inject

        interface Service
        @Inject class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        @Inject class Consumer(val serviceFactory: () -> Service)
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val consumer = index.consumerEntryAt(declarations.parameter("serviceFactory"))!!
    assertTrue(index.bindingsFor(consumer).isEmpty())
  }

  fun testInternalHintsFromProjectOwnedBinariesAreFilteredWithoutFriendship() {
    module.withMetroLibFixtureLibrary(withinProject = true) {
      val file =
        myFixture.configureByText(
          "FriendGraph.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibHidden

          @DependencyGraph(AppScope::class)
          interface FriendGraph {
            val hidden: LibHidden
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      // Project-path ownership is not a visibility relationship; internal hints still require a
      // formal friend/associated compilation relationship.
      val hiddenAccessor = index.consumerEntryAt(declarations.property("hidden"))!!
      assertTrue(index.bindingsFor(hiddenAccessor).isEmpty())
    }
  }

  fun testAssistedFactoriesProvideTheirOwnType() {
    val file =
      myFixture.configureByText(
        "Assisted.kt",
        """
        package test

        import dev.zacsweers.metro.Assisted
        import dev.zacsweers.metro.AssistedFactory
        import dev.zacsweers.metro.AssistedInject
        import dev.zacsweers.metro.Inject

        class Engine @AssistedInject constructor(@Assisted val id: String)

        @AssistedFactory
        interface EngineFactory {
          fun create(id: String): Engine
        }

        @Inject class EngineUser(val factory: EngineFactory)
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val factoryEntry = index.bindingEntriesAt(declarations.klass("EngineFactory")).single()
    assertEquals("assisted factory", factoryEntry.label)
    assertEquals("test.EngineFactory", factoryEntry.typeKey.renderedType)
    assertEquals("Engine", factoryEntry.implementationName)

    val factoryParam = index.consumerEntryAt(declarations.parameter("factory"))!!
    assertEquals(
      listOf("assisted factory"),
      index.bindingsFor(factoryParam).map { it.label },
    )
  }

  fun testDefaultBindingSuppliesImplicitBoundType() {
    val file =
      myFixture.configureByText(
        "Defaults.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesIntoSet
        import dev.zacsweers.metro.DefaultBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        @DefaultBinding<BaseFactory<*>>
        interface BaseFactory<T : BaseFactory<T>>

        interface OtherMarker

        @ContributesIntoSet(AppScope::class)
        @Inject
        class HomeFactory : BaseFactory<HomeFactory>, OtherMarker

        @DependencyGraph(AppScope::class)
        interface DefaultsGraph {
          val factories: Set<BaseFactory<*>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // Two supertypes, no explicit binding<T>() — the @DefaultBinding supertype decides
    val accessor = index.consumerEntryAt(declarations.property("factories"))!!
    val contributors = index.bindingsFor(accessor)
    assertEquals(listOf("HomeFactory"), contributors.map { it.implementationName })
    assertEquals("test.BaseFactory<*>", contributors.single().typeKey.renderedType)
  }

  fun testAmbiguousDefaultBindingsLeaveContributionUnresolved() {
    val file =
      myFixture.configureByText(
        "AmbiguousDefaults.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DefaultBinding
        import dev.zacsweers.metro.Inject

        @DefaultBinding<MarkerA>
        interface MarkerA

        @DefaultBinding<MarkerB>
        interface MarkerB

        @ContributesBinding(AppScope::class)
        @Inject
        class Impl : MarkerA, MarkerB
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    // Two supertypes both declare @DefaultBinding, so the bound type is ambiguous — no contributed
    // binding is originated (matching the compiler, rather than arbitrarily picking the first).
    val entries = index.bindingEntriesAt(declarations.klass("Impl"))
    assertTrue(entries.any { it.label == "injected class" })
    assertTrue(entries.none { it.label == "contributed binding" })
  }

  fun testClassKeyMapContributionsResolve() {
    val file =
      myFixture.configureByText(
        "ClassKeys.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ClassKey
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.IntoMap
        import dev.zacsweers.metro.Provides
        import kotlin.reflect.KClass

        interface Handler
        class FooHandler : Handler
        class Foo

        interface HandlerProviders {
          @Provides @IntoMap @ClassKey(Foo::class) fun fooHandler(): Handler = FooHandler()
        }

        @Inject class HandlerUser(val handlers: Map<KClass<*>, Handler>)
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val handlersParam = index.consumerEntryAt(declarations.parameter("handlers"))!!
    val contributors = index.bindingsFor(handlersParam)
    assertEquals(listOf("multibinding contribution"), contributors.map { it.label })
  }

  fun testReplacedContributionsLosePerGraph() {
    val file =
      myFixture.configureByText(
        "Replaces.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        interface Repo

        @ContributesBinding(AppScope::class)
        @Inject
        class RealRepo : Repo

        @ContributesBinding(AppScope::class, replaces = [RealRepo::class])
        @Inject
        class FakeRepo : Repo

        @DependencyGraph(AppScope::class)
        interface ReplacesGraph {
          val repo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val accessor = index.consumerEntryAt(declarations.property("repo"))!!
    val resolution = index.resolveConsumer(accessor)
    assertEquals(2, resolution.global.size)
    // In the graph, the replacement wins
    assertEquals(
      listOf("FakeRepo"),
      resolution.uniformBindings.orEmpty().map { it.implementationName },
    )
    val graph = index.graphEntryAt(declarations.klass("ReplacesGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(graph).single())!!
    assertEquals(
      listOf("FakeRepo"),
      index.contributionsFor(queryContext).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )

    val realEntry =
      index.bindingEntriesAt(declarations.klass("RealRepo")).single {
        it.label == "contributed binding"
      }
    val fakeEntry =
      index.bindingEntriesAt(declarations.klass("FakeRepo")).single {
        it.label == "contributed binding"
      }
    assertTrue(index.consumersFor(listOf(realEntry)).isEmpty())
    assertEquals(
      listOf("repo"),
      index.consumersFor(listOf(fakeEntry)).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testExcludedContributionsAreDroppedFromGraphContext() {
    val file =
      myFixture.configureByText(
        "Excludes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class NoisyThing : Thing

        @DependencyGraph(AppScope::class, excludes = [NoisyThing::class])
        interface ExcludesGraph {
          val thing: Thing
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("ExcludesGraph"))!!
    val context = index.contextsFor(graph).single()
    val queryContext = index.queryContext(context)!!
    assertTrue(context.excludes.isNotEmpty())

    val accessor = index.consumerEntryAt(declarations.property("thing"))!!
    assertTrue(index.bindingsFor(accessor, queryContext).isEmpty())
    assertTrue(index.contributionsFor(queryContext).isEmpty())
    // Global resolution still sees it as a candidate
    assertEquals(1, index.resolveConsumer(accessor).global.size)
  }

  fun testExcludedContributionKeepsItsConcreteBindingAndConsumers() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Api
        interface Client
        interface Monitor
        interface Tracker

        @HasMemberInjections
        abstract class BaseApi {
          @Inject lateinit var monitor: Monitor
        }

        @Inject @ContributesBinding(AppScope::class)
        class RealApi(val client: Client) : BaseApi(), Api {
          @Inject lateinit var tracker: Tracker
        }

        interface Providers {
          @Provides fun provideClient(): Client = object : Client {}
          @Provides fun provideMonitor(): Monitor = object : Monitor {}
          @Provides fun provideTracker(): Tracker = object : Tracker {}
        }

        @DependencyGraph(AppScope::class, excludes = [RealApi::class])
        interface AppGraph : Providers {
          val api: RealApi
        }

        @DependencyGraph
        interface OtherGraph : Providers {
          val otherApi: RealApi
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val concreteBinding =
      index.bindingEntriesAt(declarations.klass("RealApi")).single {
        it is KaBinding.ConstructorInjected
      }
    val accessor = index.consumerEntryAt(declarations.property("api"))!!
    assertEquals(listOf(concreteBinding), index.resolveConsumer(accessor).uniformBindings)

    val dependencySites =
      listOf(
        declarations.parameter("client") to "provideClient",
        declarations.property("monitor") to "provideMonitor",
        declarations.property("tracker") to "provideTracker",
      )
    for ((declaration, providerName) in dependencySites) {
      val consumer = index.consumerEntryAt(declaration)!!
      val resolution = index.resolveConsumer(consumer)
      assertEquals(
        setOf("AppGraph", "OtherGraph"),
        resolution.perContext.keys.map { it.graph.name }.toSet(),
      )
      for (bindings in resolution.perContext.values) {
        assertEquals(
          listOf(providerName),
          bindings.map { (it.pointer.element as? KtNamedDeclaration)?.name },
        )
      }
      val providers =
        index.bindings.filter { (it.pointer.element as? KtNamedDeclaration)?.name == providerName }
      assertTrue(index.consumersFor(providers).contains(consumer))
    }

    val validationService = project.service<MetroGraphValidationService>()
    for (graph in index.graphs) {
      val result =
        validationService.validate(file, index.contextsFor(graph).single()).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testBindingExplanationUsesContributionSelectionReasons() {
    // The bootstrap runtime predates native contribution priorities, so declare the test shape.
    project.setMetroOptions("custom-contributes-binding" to "test/PrioritizedBinding")
    val file =
      myFixture.configureMetroFile(
        """
      import kotlin.reflect.KClass

      annotation class PrioritizedBinding(
        val scope: KClass<*>,
        val priority: Int = Int.MIN_VALUE,
        val replaces: Array<KClass<*>> = [],
      )

      interface Service
      @Inject @PrioritizedBinding(AppScope::class, priority = 1) class Slow : Service
      @Inject @PrioritizedBinding(AppScope::class, priority = 2) class Fast : Service
      @Inject @PrioritizedBinding(AppScope::class) class Retired : Service
      @Inject @PrioritizedBinding(AppScope::class, replaces = [Retired::class], priority = 1) class Backup : Service
      @Inject @PrioritizedBinding(AppScope::class, priority = 3) class Excluded : Service
      @DependencyGraph(AppScope::class, excludes = [Excluded::class])
      interface AppGraph { val service: Service }
      """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val consumer = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!
    index.withResolutionSession { session ->
      val context = session.contextsFor(index.graphs.single()).single()
      val query = session.queryContext(context)!!
      val explanation = index.explainBindings(session, consumer, query)
      assertEquals(context.path, explanation.context.path)
      assertEquals(session.bindingsFor(consumer, query), explanation.selected)
      assertEquals(listOf("Fast"), explanation.selected.map { it.implementationName })
      val candidates =
        explanation.candidates.associateBy { it.binding.originClassId?.shortClassName?.asString() }
      assertTrue(candidates.getValue("Fast").selected)
      assertEquals(BindingRejection.LOWER_PRIORITY, candidates.getValue("Slow").rejection)
      assertEquals(BindingRejection.LOWER_PRIORITY, candidates.getValue("Backup").rejection)
      assertEquals(BindingRejection.REPLACED, candidates.getValue("Retired").rejection)
      assertEquals(BindingRejection.EXCLUDED, candidates.getValue("Excluded").rejection)
    }
  }

  fun testBindingExplanationShowsExplicitPrecedenceAndQualifierAlternatives() {
    val file =
      myFixture.configureMetroFile(
        """
      @Inject class Service
      @DependencyGraph interface AppGraph {
        val service: Service
        @Provides fun service(): Service = Service()
        @Provides @Named("other") fun other(): Service = Service()
      }
      """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val consumer = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!
    index.withResolutionSession { session ->
      val query = session.queryContext(session.contextsFor(index.graphs.single()).single())!!
      val explanation = index.explainBindings(session, consumer, query)
      assertEquals(session.bindingsFor(consumer, query), explanation.selected)
      assertEquals(1, explanation.selected.size)
      val implicit = explanation.candidates.single { it.binding is KaBinding.ConstructorInjected }
      assertFalse(implicit.selected)
      assertTrue(implicit.reason.contains("higher precedence"))
      val qualified = explanation.candidates.single { it.binding.typeKey.qualifier != null }
      assertFalse(qualified.selected)
      assertTrue(qualified.reason.contains("different qualifier"))
    }
  }

  fun testExplicitProviderIsTheOnlyEditorTargetForAnInjectedClass() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service

        @DependencyGraph
        interface AppGraph {
          val service: Service
          @Provides fun provideService(): Service = Service()
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("service"))!!
    val provider = index.bindingEntriesAt(declarations.function("provideService")).single()
    val constructor = index.bindingEntriesAt(declarations.klass("Service")).single()
    val context = index.contextsFor(index.graphs.single()).single()

    assertEquals(listOf(provider), index.resolveConsumer(consumer).uniformBindings)
    assertEquals(listOf(provider), index.bindingsFor(consumer, index.queryContext(context)!!))
    assertTrue(index.consumersFor(listOf(constructor)).isEmpty())
    assertEquals(listOf(consumer), index.consumersFor(listOf(provider)))
  }

  fun testExplicitProviderDoesNotMakeAssistedTargetNavigable() {
    val file =
      myFixture.configureMetroFile(
        """
        @AssistedInject class Widget(@Assisted val name: String)

        @DependencyGraph
        interface AppGraph {
          val widget: Widget
          @Provides fun provideWidget(): Widget = Widget("manual")
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val consumer = index.consumerEntryAt(file.declarationsIncludingNested().property("widget"))!!

    assertTrue(index.resolveConsumer(consumer).uniformBindings.orEmpty().isEmpty())
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(index.graphs.single()).single())
        .requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testExplicitCollectionProviderHidesUnusedContributorsFromEditorQueries() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val names: Set<String>
          @Provides fun provideNames(): Set<String> = setOf("explicit")
          @Provides @IntoSet fun contributeName(): String = "contributed"
          @Multibinds fun declareNames(): Set<String>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("names"))!!
    val provider = index.bindingEntriesAt(declarations.function("provideNames")).single()
    val contribution = index.bindingEntriesAt(declarations.function("contributeName")).single()
    val declaration = index.bindingEntriesAt(declarations.function("declareNames")).single()

    assertEquals(listOf(provider), index.resolveConsumer(consumer).uniformBindings)
    assertTrue(index.consumersFor(listOf(contribution, declaration)).isEmpty())
    val declarationConsumer = index.consumerEntryAt(declarations.function("declareNames"))!!
    assertEquals(setOf(consumer, declarationConsumer), index.consumersFor(listOf(provider)).toSet())

    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(index.graphs.single()).single())
        .requireCompleted()
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testCollectionNavigationShowsContributorsWithoutItsDeclaration() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val names: Set<String>
          @Provides @IntoSet fun contributeName(): String = "contributed"
          @Multibinds fun declareNames(): Set<String>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("names"))!!
    val contribution = index.bindingEntriesAt(declarations.function("contributeName")).single()

    assertEquals(listOf(contribution), index.resolveConsumer(consumer).uniformBindings)
  }

  fun testConflictingExplicitProvidersRemainVisibleToEditorAndValidation() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service

        @DependencyGraph
        interface AppGraph {
          val service: Service
          @Provides fun firstService(): Service = Service()
          @Provides fun secondService(): Service = Service()
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("service"))!!

    assertEquals(
      setOf("firstService", "secondService"),
      index
        .resolveConsumer(consumer)
        .uniformBindings
        .orEmpty()
        .map {
          (it.pointer.element as? KtNamedDeclaration)?.name
        }
        .toSet(),
    )
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(index.graphs.single()).single())
        .requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_BINDING), result.diagnostics.map { it.id })
  }

  fun testGeneratedGraphAliasIsAnEditorTarget() {
    val file =
      myFixture.configureMetroFile(
        """
        interface EntryPoint
        @Inject class Client(val entryPoint: EntryPoint)

        @DependencyGraph
        interface AppGraph : EntryPoint {
          val client: Client
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.parameter("entryPoint"))!!
    val selected = index.resolveConsumer(consumer).uniformBindings.orEmpty().single()

    assertTrue(selected is KaBinding.Alias)
    assertSame(declarations.klass("AppGraph"), selected.pointer.element)
  }

  fun testBindingContainersGateBindingsPerGraph() {
    val file =
      myFixture.configureByText(
        "Containers.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Provides

        class Client
        class Api

        @BindingContainer
        object NetBindings {
          @Provides fun client(): Client = Client()
        }

        @BindingContainer(includes = [NetBindings::class])
        object AppBindings {
          @Provides fun api(client: Client): Api = Api()
        }

        @DependencyGraph(AppScope::class, bindingContainers = [AppBindings::class])
        interface WiredGraph {
          val api: Api
          val client: Client
        }

        @DependencyGraph
        interface UnwiredGraph {
          val unwiredClient: Client
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val wired = index.contextsFor(index.graphEntryAt(declarations.klass("WiredGraph"))!!).single()
    // Transitive container includes are expanded
    assertEquals(2, index.queryContext(wired)!!.containers.size)
    val clientAccessor = index.consumerEntryAt(declarations.property("client"))!!
    assertEquals(1, index.bindingsFor(clientAccessor, index.queryContext(wired)!!).size)

    val unwired =
      index.contextsFor(index.graphEntryAt(declarations.klass("UnwiredGraph"))!!).single()
    val unwiredAccessor = index.consumerEntryAt(declarations.property("unwiredClient"))!!
    assertTrue(index.bindingsFor(unwiredAccessor, index.queryContext(unwired)!!).isEmpty())
  }

  fun testIncludedDependencyAccessorsProvide() {
    val file =
      myFixture.configureByText(
        "Includes.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes

        class Client

        interface FactoryBase<G, D> {
          fun create(@Includes deps: D): G
        }

        interface NetworkDeps<T> {
          val client: T
        }

        @DependencyGraph(AppScope::class)
        interface IncludesGraph {
          val deps: NetworkDeps<Client>
          val graphClient: Client

          @DependencyGraph.Factory
          interface Factory : FactoryBase<IncludesGraph, NetworkDeps<Client>>
        }

        @DependencyGraph(AppScope::class)
        interface OtherGraph {
          val otherClient: Client

          @DependencyGraph.Factory
          interface Factory : FactoryBase<OtherGraph, NetworkDeps<Client>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val graph = index.graphEntryAt(declarations.klass("IncludesGraph"))!!
    val context = index.contextsFor(graph).single()
    val queryContext = index.queryContext(context)!!
    assertEquals(
      setOf("test.NetworkDeps<test.Client>"),
      context.includedDependencies.mapTo(mutableSetOf()) { it.renderedType },
    )

    val accessor = index.consumerEntryAt(declarations.property("graphClient"))!!
    val bindings = index.bindingsFor(accessor, queryContext)
    assertEquals(listOf("included dependency accessor"), bindings.map { it.label })
    assertEquals("test.Client", bindings.single().typeKey.renderedType)
    assertEquals(
      listOf("test.NetworkDeps<test.Client>"),
      bindings.single().dependencies.map { it.typeKey.renderedType },
    )
    // Anchored at the dependency's accessor declaration
    assertEquals(
      "client",
      (bindings.single().pointer.element as? KtNamedDeclaration)?.name,
    )

    val ownerAccessor = index.consumerEntryAt(declarations.property("deps"))!!
    assertEquals(
      listOf("instance binding"),
      index.bindingsFor(ownerAccessor, queryContext).map { it.label },
    )

    val otherGraph = index.graphEntryAt(declarations.klass("OtherGraph"))!!
    val otherContext = index.queryContext(index.contextsFor(otherGraph).single())!!
    val otherAccessor = index.consumerEntryAt(declarations.property("otherClient"))!!
    assertEquals(
      listOf("included dependency accessor"),
      index.bindingsFor(otherAccessor, otherContext).map { it.label },
    )

    // The concrete factory input is shared rather than recreated once per including graph.
    assertEquals(
      1,
      index.bindings.filterIsInstance<KaBinding.GraphDependency>().count {
        it.ownerKey.renderedType == "test.NetworkDeps<test.Client>"
      },
    )
    assertEquals(
      1,
      index.bindings.filterIsInstance<KaBinding.BoundInstance>().count {
        it.isGraphInput && it.typeKey.renderedType == "test.NetworkDeps<test.Client>"
      },
    )
  }

  fun testIncludedGenericBindingContainersUseConcreteTypes() {
    val file =
      myFixture.configureByText(
        "GenericIncludes.kt",
        """
        package test

        import dev.zacsweers.metro.BindingContainer
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes
        import dev.zacsweers.metro.Provides

        class Box<T>(val value: T)

        @BindingContainer
        interface GenericBindings<T> {
          @Provides fun value(): T = error("not called")
          @Provides fun box(value: T): Box<T> = Box(value)

          companion object {
            @Provides fun count(): Long = 1L
          }
        }

        @DependencyGraph
        interface StringGraph {
          val stringValue: String
          val stringBox: Box<String>
          val count: Long

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bindings: GenericBindings<String>): StringGraph
          }
        }

        @DependencyGraph
        interface IntGraph {
          val intValue: Int

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bindings: GenericBindings<Int>): IntGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val stringGraph = index.graphEntryAt(declarations.klass("StringGraph"))!!
    val stringContext = index.contextsFor(stringGraph).single()
    val stringQueryContext = index.queryContext(stringContext)!!
    assertEquals(
      setOf("test.GenericBindings<kotlin.String>"),
      stringContext.includedBindingContainers.mapTo(mutableSetOf()) { it.renderedType },
    )

    val valueAccessor = index.consumerEntryAt(declarations.property("stringValue"))!!
    assertEquals(
      listOf("kotlin.String"),
      index.bindingsFor(valueAccessor, stringQueryContext).map { it.typeKey.renderedType },
    )
    val boxAccessor = index.consumerEntryAt(declarations.property("stringBox"))!!
    val boxBinding = index.bindingsFor(boxAccessor, stringQueryContext).single()
    assertEquals("test.Box<kotlin.String>", boxBinding.typeKey.renderedType)
    assertEquals(
      listOf("test.GenericBindings<kotlin.String>", "kotlin.String"),
      boxBinding.dependencies.map { it.typeKey.renderedType },
    )
    val countAccessor = index.consumerEntryAt(declarations.property("count"))!!
    assertEquals(1, index.bindingsFor(countAccessor, stringQueryContext).size)

    val intGraph = index.graphEntryAt(declarations.klass("IntGraph"))!!
    val intQueryContext = index.queryContext(index.contextsFor(intGraph).single())!!
    val intAccessor = index.consumerEntryAt(declarations.property("intValue"))!!
    assertEquals(
      listOf("kotlin.Int"),
      index.bindingsFor(intAccessor, intQueryContext).map { it.typeKey.renderedType },
    )
    assertTrue(index.bindingsFor(valueAccessor, intQueryContext).isEmpty())
  }

  fun testIncludedDependencyShardTracksAccessorFileChanges() {
    val dependencyFile =
      myFixture.addFileToProject(
        "test/NetworkDeps.kt",
        """
        package test

        class First
        class Second

        interface NetworkDeps {
          val first: First
        }
        """
          .trimIndent(),
      ) as KtFile
    val graphFile =
      myFixture.configureByText(
        "Graph.kt",
        """
        package test

        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes

        @DependencyGraph
        interface AppGraph {
          val second: Second

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes deps: NetworkDeps): AppGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val declarations = graphFile.declarationsIncludingNested()
    val accessor = declarations.property("second")

    val initialIndex = project.service<MetroResolutionService>().awaitIndex(graphFile)
    val initialGraph = initialIndex.graphEntryAt(declarations.klass("AppGraph"))!!
    val initialContext =
      initialIndex.queryContext(initialIndex.contextsFor(initialGraph).single())!!
    val initialConsumer = initialIndex.consumerEntryAt(accessor)!!
    assertTrue(initialIndex.bindingsFor(initialConsumer, initialContext).isEmpty())

    myFixture.openFileInEditor(dependencyFile.virtualFile)
    myFixture.editor.caretModel.moveToOffset(dependencyFile.text.lastIndexOf('}'))
    myFixture.type("  val second: Second\n")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updatedIndex = project.service<MetroResolutionService>().awaitIndex(graphFile)
    val updatedGraph = updatedIndex.graphEntryAt(declarations.klass("AppGraph"))!!
    val updatedContext =
      updatedIndex.queryContext(updatedIndex.contextsFor(updatedGraph).single())!!
    val updatedConsumer = updatedIndex.consumerEntryAt(accessor)!!
    assertEquals(
      listOf("included dependency accessor"),
      updatedIndex.bindingsFor(updatedConsumer, updatedContext).map { it.label },
    )
  }

  fun testGraphExtensionsInheritParentContext() {
    val file =
      myFixture.configureByText(
        "Extensions.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.GraphExtension
        import dev.zacsweers.metro.Inject

        abstract class ChildScope
        abstract class OtherScope

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class RealThing : Thing

        @ContributesBinding(OtherScope::class)
        @Inject
        class OtherThing : Thing

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          val thing: Thing

          @GraphExtension.Factory
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val childGraph: ChildGraph
          val childFactory: ChildGraph.Factory
        }

        @DependencyGraph(OtherScope::class)
        interface OtherParentGraph {
          val childGraph: ChildGraph
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    assertTrue(child.isExtension)
    val childContexts = index.contextsFor(child)
    assertEquals(2, childContexts.size)

    // The child's accessor resolves through every parent scope that creates it
    val accessor = index.consumerEntryAt(declarations.property("thing"))!!
    val bindings = childContexts.flatMap { childContext ->
      index.bindingsFor(accessor, index.queryContext(childContext)!!)
    }
    assertEquals(setOf("RealThing", "OtherThing"), bindings.map { it.implementationName }.toSet())
    val resolutionByParent =
      index.resolveConsumer(accessor).perContext.mapKeys { (context, _) -> context.chain[1].name }
    assertEquals(
      mapOf(
        "ParentGraph" to listOf("RealThing"),
        "OtherParentGraph" to listOf("OtherThing"),
      ),
      resolutionByParent.mapValues { (_, parentBindings) ->
        parentBindings.map { it.implementationName }
      },
    )
    val resolution = index.resolveConsumer(accessor)
    assertNull(resolution.uniformBindings)
    assertEquals(
      setOf("RealThing", "OtherThing"),
      resolution.candidateBindings.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertTrue(resolution.emptyContexts.isEmpty())

    // But parent contexts do not include child-scoped bindings beyond their own scope
    val parent = index.contextsFor(index.graphEntryAt(declarations.klass("ParentGraph"))!!).single()
    assertEquals(1, parent.chain.size)
    val otherParent =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherParentGraph"))!!).single()
    assertEquals(1, otherParent.chain.size)

    // Direct child-graph creation is not a consumer. A separate factory is a real accessor root.
    declarations
      .filterIsInstance<KtProperty>()
      .filter { it.name == "childGraph" }
      .forEach { assertNull(index.consumerEntryAt(it)) }
    val factoryAccessor = checkNotNull(index.consumerEntryAt(declarations.property("childFactory")))
    assertEquals(
      dev.zacsweers.metro.idea.model.ConsumerEntry.GraphRequestKind.ACCESSOR,
      factoryAccessor.graphRequestKind,
    )
    assertEquals("test.ChildGraph.Factory", factoryAccessor.key.renderedType)
    assertNull(factoryAccessor.key.qualifier)
    assertEquals(parent.graph.declarationId, factoryAccessor.graphId)

    // The child aggregates only its own scope; parent-scope contributions are inherited
    val childQueryContexts = childContexts.map { index.queryContext(it)!! }
    assertTrue(childQueryContexts.all { index.contributionsFor(it).isEmpty() })
    assertEquals(
      listOf(1, 1),
      childQueryContexts.map { index.inheritedContributionsFor(it).size },
    )
    val parentQueryContext = index.queryContext(parent)!!
    assertEquals(1, index.contributionsFor(parentQueryContext).size)
    assertTrue(index.inheritedContributionsFor(parentQueryContext).isEmpty())
    val otherParentQueryContext = index.queryContext(otherParent)!!
    assertEquals(1, index.contributionsFor(otherParentQueryContext).size)
    assertTrue(index.inheritedContributionsFor(otherParentQueryContext).isEmpty())
  }

  fun testConsumerResolutionIsScopedToOwningGraph() {
    val file =
      myFixture.configureByText(
        "ScopedConsumers.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        abstract class OtherScope

        interface Repo

        @ContributesBinding(AppScope::class)
        @Inject
        class AppRepo : Repo

        @ContributesBinding(OtherScope::class)
        @Inject
        class OtherRepo : Repo

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val appRepo: Repo
        }

        @DependencyGraph(OtherScope::class)
        interface OtherGraph {
          val otherRepo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val appRepo = index.consumerEntryAt(declarations.property("appRepo"))!!
    val appResolution = index.resolveConsumer(appRepo)
    assertEquals(
      listOf("AppRepo"),
      appResolution.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(listOf("AppGraph"), appResolution.perContext.keys.map { it.graph.name })

    val otherRepo = index.consumerEntryAt(declarations.property("otherRepo"))!!
    val otherResolution = index.resolveConsumer(otherRepo)
    assertEquals(
      listOf("OtherRepo"),
      otherResolution.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(listOf("OtherGraph"), otherResolution.perContext.keys.map { it.graph.name })
  }

  fun testMemberInjectionSitesOnlyBelongToGraphsThatInjectTheirOwner() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        class Screen {
          @Inject lateinit var service: Service
        }

        @DependencyGraph
        interface ScreenGraph {
          @Provides fun provideService(): Service = object : Service {}
          fun inject(screen: Screen)
        }

        @DependencyGraph
        interface OtherGraph {
          @Provides fun provideOtherService(): Service = object : Service {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val member = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!
    val resolution = index.resolveConsumer(member)

    assertEquals(listOf("ScreenGraph"), resolution.perContext.keys.map { it.graph.name })
    assertEquals(
      listOf("provideService"),
      resolution.uniformBindings.orEmpty().mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testMarkedInheritedMemberSitesFollowInjectedSubclass() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @HasMemberInjections
        abstract class BaseScreen {
          @Inject lateinit var service: Service
        }

        class Screen : BaseScreen()

        @DependencyGraph
        interface ScreenGraph {
          @Provides fun provideService(): Service = object : Service {}
          fun inject(screen: Screen)
        }

        @DependencyGraph
        interface OtherGraph
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val member = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      listOf("ScreenGraph"),
      index.resolveConsumer(member).perContext.keys.map { it.graph.name },
    )
  }

  fun testInheritedMemberEditsRebuildInjectedSubclass() {
    val base =
      myFixture.addFileToProject(
        "test/BaseScreen.kt",
        """
        package test

        import dev.zacsweers.metro.HasMemberInjections
        import dev.zacsweers.metro.Inject

        @HasMemberInjections
        abstract class BaseScreen {
          @Inject lateinit var service: OldService
        }
        """
          .trimIndent(),
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface OldService
        interface NewService

        @Inject class Screen : BaseScreen()
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial =
      service.awaitIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
    assertEquals(listOf("test.OldService"), initial.dependencies.map { it.typeKey.renderedType })

    myFixture.openFileInEditor(base.virtualFile)
    val typeOffset = base.text.indexOf("OldService")
    myFixture.editor.selectionModel.setSelection(typeOffset, typeOffset + "OldService".length)
    myFixture.type("NewService")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service.awaitIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
    assertEquals(listOf("test.NewService"), updated.dependencies.map { it.typeKey.renderedType })

    val memberText = "@Inject lateinit var service: NewService"
    val memberOffset = base.text.indexOf(memberText)
    WriteCommandAction.runWriteCommandAction(project) {
      myFixture.editor.document.deleteString(memberOffset, memberOffset + memberText.length)
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val removed =
      service.awaitIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
    assertTrue(removed.dependencies.isEmpty())
  }

  fun testAddingMemberInjectionMarkerRebuildsInjectedSubclass() {
    val base =
      myFixture.addFileToProject(
        "test/BaseScreen.kt",
        """
        package test

        import dev.zacsweers.metro.Inject

        abstract class BaseScreen {
          @Inject lateinit var service: Service
        }
        """
          .trimIndent(),
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject class Screen : BaseScreen()
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial =
      service.awaitIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
    assertTrue(initial.dependencies.isEmpty())

    myFixture.openFileInEditor(base.virtualFile)
    myFixture.editor.caretModel.moveToOffset(base.text.indexOf("abstract class"))
    myFixture.type("@dev.zacsweers.metro.HasMemberInjections\n")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated =
      service.awaitIndex(file).bindings.single { it.typeKey.renderedType == "test.Screen" }
    assertEquals(listOf("test.Service"), updated.dependencies.map { it.typeKey.renderedType })
  }

  fun testQualifierDefaultsMatchExplicitValues() {
    val file =
      myFixture.configureMetroFile(
        """
        @Qualifier
        annotation class Endpoint(val name: String = "main", val version: Int = 1)

        interface Service

        @DependencyGraph
        interface AppGraph {
          @Endpoint(version = 1, name = "main") val service: Service
          @Provides @Endpoint fun provideService(): Service = object : Service {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      listOf("provideService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testChangingQualifierDefaultsInvalidatesDependentShards() {
    val qualifier =
      myFixture.addFileToProject(
        "test/Endpoint.kt",
        """
        package test

        import dev.zacsweers.metro.Qualifier

        @Qualifier annotation class Endpoint(val name: String = "main")
        """
          .trimIndent(),
      )
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @DependencyGraph
        interface AppGraph {
          @Endpoint("main") val service: Service
          @Provides @Endpoint fun provideService(): Service = object : Service {}
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val declarations = file.declarationsIncludingNested()
    val initial = service.awaitIndex(file)
    val initialConsumer = initial.consumerEntryAt(declarations.property("service"))!!
    assertEquals(1, initial.resolveConsumer(initialConsumer).uniformBindings.orEmpty().size)

    myFixture.openFileInEditor(qualifier.virtualFile)
    val defaultValue = qualifier.text.indexOf("main")
    myFixture.editor.selectionModel.setSelection(defaultValue, defaultValue + "main".length)
    myFixture.type("other")
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val updated = service.awaitIndex(file)
    val updatedConsumer = updated.consumerEntryAt(declarations.property("service"))!!
    assertNotSame(initial, updated)
    assertTrue(updated.resolveConsumer(updatedConsumer).uniformBindings.orEmpty().isEmpty())
  }

  fun testQualifierEnumClassAndArrayDefaultsMatchExplicitValues() {
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        enum class Flavor { DEFAULT }

        @Qualifier
        annotation class Endpoint(
          val flavor: Flavor = Flavor.DEFAULT,
          val type: KClass<*> = String::class,
          val tags: Array<String> = ["primary", "backup"],
        )

        interface Service

        @DependencyGraph
        interface AppGraph {
          @Endpoint(tags = ["primary", "backup"], type = String::class, flavor = Flavor.DEFAULT)
          val service: Service

          @Provides @Endpoint fun provideService(): Service = object : Service {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      listOf("provideService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testContributionPrioritiesApplyWithoutAnvilInterop() {
    project.setMetroOptions("custom-contributes-binding" to "test/PrioritizedBinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedBinding(
          val scope: KClass<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject @PrioritizedBinding(AppScope::class)
        class DefaultService : Service

        @Inject @PrioritizedBinding(AppScope::class, priority = -50)
        class LowerService : Service

        @Inject @PrioritizedBinding(AppScope::class, priority = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val accessor = index.consumerEntryAt(declarations.property("service"))!!
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(graph).single())!!

    assertEquals(
      listOf("HigherService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(
      listOf("HigherService"),
      index.contributionsFor(queryContext).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testLowerPriorityBindingKeepsOtherBindingsAndSetContributions() {
    project.setMetroOptions("custom-contributes-binding" to "test/PrioritizedBinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        @Repeatable
        annotation class PrioritizedBinding(
          val scope: KClass<*>,
          val boundType: KClass<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service
        interface OtherService

        @Inject
        @PrioritizedBinding(AppScope::class, boundType = Service::class, priority = 10)
        @PrioritizedBinding(AppScope::class, boundType = OtherService::class, priority = 100)
        @ContributesIntoSet(AppScope::class, binding = binding<OtherService>())
        class SharedService : Service, OtherService

        @Inject
        @PrioritizedBinding(AppScope::class, boundType = Service::class, priority = 50)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val other: OtherService
          val others: Set<OtherService>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(graph).single())!!

    fun implementations(accessor: String): List<String?> {
      val consumer = index.consumerEntryAt(declarations.property(accessor))!!
      return index.bindingsFor(consumer, queryContext).map { it.implementationName }
    }

    assertEquals(listOf("HigherService"), implementations("service"))
    assertEquals(listOf("SharedService"), implementations("other"))
    assertEquals(listOf("SharedService"), implementations("others"))
    assertEquals(
      setOf("SharedService", "HigherService"),
      index.contributionsFor(queryContext).mapNotNullTo(mutableSetOf()) {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testTypeUseQualifiersKeepRepeatedPrioritizedBindingsIndependent() {
    project.setMetroOptions("custom-contributes-binding" to "test/PrioritizedBinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        @Repeatable
        annotation class PrioritizedBinding(
          val scope: KClass<*>,
          val binding: binding<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject
        @PrioritizedBinding(
          AppScope::class,
          binding = binding<@Named("first") Service>(),
          priority = 10,
        )
        @PrioritizedBinding(
          AppScope::class,
          binding = binding<@Named("second") Service>(),
          priority = 100,
        )
        class SharedService : Service

        @Inject
        @PrioritizedBinding(
          AppScope::class,
          binding = binding<@Named("first") Service>(),
          priority = 50,
        )
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Named("first") val first: Service
          @Named("second") val second: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val first = index.consumerEntryAt(declarations.property("first"))!!
    val second = index.consumerEntryAt(declarations.property("second"))!!

    assertEquals(
      listOf("HigherService"),
      index.resolveConsumer(first).uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(
      listOf("SharedService"),
      index.resolveConsumer(second).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testSetContributionsStayAdditiveAndPreserveAuthoredElements() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        interface OtherService

        @Inject
        @ContributesBinding(AppScope::class, binding = binding<OtherService>())
        @ContributesIntoSet(AppScope::class, binding = binding<Service>())
        class FirstService : Service, OtherService

        @Inject @ContributesIntoSet(AppScope::class)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Set<Service>
          val other: OtherService

          @Provides @IntoSet fun authoredService(): Service = object : Service {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val services = index.consumerEntryAt(declarations.property("services"))!!
    val other = index.consumerEntryAt(declarations.property("other"))!!

    assertEquals(
      setOf("FirstService", "SecondService", "authoredService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().mapTo(mutableSetOf()) { binding ->
        binding.implementationName ?: (binding.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertEquals(
      listOf("FirstService"),
      index.resolveConsumer(other).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testCustomSetContributionsAreAdditive() {
    project.setMetroOptions("custom-contributes-into-set" to "test/CustomSetContribution")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class CustomSetContribution(val scope: KClass<*>)

        interface Service

        @Inject @CustomSetContribution(AppScope::class)
        class FirstService : Service

        @Inject @CustomSetContribution(AppScope::class)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val services = index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!

    assertEquals(
      setOf("FirstService", "SecondService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testGeneratedSetContributionProvidersStayAdditive() {
    project.setMetroOptions("generate-contribution-providers" to "true")
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject @ContributesIntoSet(AppScope::class)
        class FirstService : Service

        @Inject @ContributesIntoSet(AppScope::class)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val services = index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!
    val matching = index.resolveConsumer(services).uniformBindings.orEmpty()

    assertEquals(
      setOf("FirstService", "SecondService"),
      matching.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertTrue(matching.all { it is KaBinding.Provided })
  }

  fun testExplicitSetContributionReplacementStillApplies() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject @ContributesIntoSet(AppScope::class)
        class ReplacedService : Service

        @Inject @ContributesIntoSet(AppScope::class, replaces = [ReplacedService::class])
        class ReplacingService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val services = index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!

    assertEquals(
      listOf("ReplacingService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testExcludedSetContributionsAreDroppedFromGraphContext() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject @ContributesIntoSet(AppScope::class)
        class ExcludedService : Service

        @Inject @ContributesIntoSet(AppScope::class)
        class RetainedService : Service

        @DependencyGraph(AppScope::class, excludes = [ExcludedService::class])
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val services = index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!

    assertEquals(
      listOf("RetainedService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testSetContributionsFromDifferentGraphScopesStayAdditive() {
    val file =
      myFixture.configureMetroFile(
        """
        abstract class ChildScope

        interface Service

        @Inject @ContributesIntoSet(AppScope::class)
        class ParentService : Service

        @Inject @ContributesIntoSet(ChildScope::class)
        class ChildService : Service

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          val services: Set<Service>
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val services = index.consumerEntryAt(declarations.property("services"))!!
    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(child).single())!!

    assertEquals(
      setOf("ParentService", "ChildService"),
      index.bindingsFor(services, queryContext).mapTo(mutableSetOf()) { it.implementationName },
    )
  }

  fun testKeyedCustomSetAnnotationsBecomePrioritizedMapContributions() {
    project.setMetroOptions("custom-contributes-into-set" to "test/PrioritizedMultibinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMultibinding(
          val scope: KClass<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject @StringKey("shared")
        @PrioritizedMultibinding(AppScope::class, priority = 10)
        class LowerMapService : Service

        @Inject @StringKey("shared")
        @PrioritizedMultibinding(AppScope::class, priority = 100)
        class HigherMapService : Service

        @Inject @StringKey("other")
        @PrioritizedMultibinding(AppScope::class, priority = 100)
        class OtherMapService : Service

        @Inject @PrioritizedMultibinding(AppScope::class)
        class SetService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val mapped: Map<String, Service>
          val collected: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val mapped = index.consumerEntryAt(declarations.property("mapped"))!!
    val collected = index.consumerEntryAt(declarations.property("collected"))!!

    assertEquals(
      setOf("HigherMapService", "OtherMapService"),
      index.resolveConsumer(mapped).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
    assertEquals(
      listOf("SetService"),
      index.resolveConsumer(collected).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testMixedOrdinaryAndMultibindingAnnotationsStayIndependent() {
    project.setMetroOptions("custom-contributes-binding" to "test/MixedContribution")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        @Repeatable
        annotation class MixedContribution(
          val scope: KClass<*>,
          val multibinding: Boolean = false,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject
        @MixedContribution(AppScope::class, priority = 5)
        @MixedContribution(AppScope::class, multibinding = true)
        class SharedService : Service

        @Inject @MixedContribution(AppScope::class, multibinding = true)
        class HigherSetService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val services: Set<Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val service = index.consumerEntryAt(declarations.property("service"))!!
    val services = index.consumerEntryAt(declarations.property("services"))!!

    assertEquals(
      listOf("SharedService"),
      index.resolveConsumer(service).uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(
      setOf("SharedService", "HigherSetService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testIgnoredImplementationQualifierPreservesBindingPriorityAndAdditiveSets() {
    project.setMetroOptions(
      "custom-contributes-binding" to "test/RankedBinding",
      "custom-contributes-into-set" to "test/SetContribution",
      "enable-dagger-anvil-interop" to "true",
    )
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class RankedBinding(
          val scope: KClass<*>,
          val ignoreQualifier: Boolean = false,
          val rank: Int,
        )

        annotation class SetContribution(
          val scope: KClass<*>,
          val ignoreQualifier: Boolean = false,
        )

        interface Service

        @Inject
        @RankedBinding(AppScope::class, rank = 10)
        @SetContribution(AppScope::class)
        class LowerService : Service

        @Inject @Named("ignored")
        @RankedBinding(AppScope::class, ignoreQualifier = true, rank = 100)
        @SetContribution(AppScope::class, ignoreQualifier = true)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val services: Set<Service>
          @Named("ignored") val implementation: HigherService
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    for (propertyName in listOf("service", "implementation")) {
      val consumer = index.consumerEntryAt(declarations.property(propertyName))!!
      assertEquals(
        listOf("HigherService"),
        index.resolveConsumer(consumer).uniformBindings.orEmpty().map { it.implementationName },
      )
    }

    val services = index.consumerEntryAt(declarations.property("services"))!!
    assertEquals(
      setOf("LowerService", "HigherService"),
      index.resolveConsumer(services).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testMapPriorityOnlyReplacesTheSameMapKey() {
    project.setMetroOptions("custom-into-map" to "test/PrioritizedMapContribution")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMapContribution(
          val scope: KClass<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject @StringKey("shared")
        @PrioritizedMapContribution(AppScope::class, priority = 10)
        class LowerService : Service

        @Inject @StringKey("shared")
        @PrioritizedMapContribution(AppScope::class, priority = 100)
        class HigherService : Service

        @Inject @StringKey("other")
        @PrioritizedMapContribution(AppScope::class, priority = 1)
        class OtherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Map<String, Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("services"))!!

    assertEquals(
      setOf("HigherService", "OtherService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testImplicitClassMapKeysDoNotCompeteByPriority() {
    project.setMetroOptions("custom-into-map" to "test/PrioritizedMapContribution")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMapContribution(
          val scope: KClass<*>,
          val binding: binding<*> = binding<Nothing>(),
          val priority: Int = Int.MIN_VALUE,
        )

        @Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
        @MapKey(implicitClassKey = true)
        annotation class ViewModelKey(val value: KClass<out ViewModel> = Nothing::class)

        abstract class ViewModel

        @Inject
        @PrioritizedMapContribution(
          AppScope::class,
          binding = binding<@ViewModelKey ViewModel>(),
          priority = 100,
        )
        class FooViewModel : ViewModel()

        @Inject @ViewModelKey
        @PrioritizedMapContribution(AppScope::class, priority = 10)
        class BarViewModel : ViewModel()

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val viewModels: Map<KClass<out ViewModel>, ViewModel>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor =
      index.consumerEntryAt(file.declarationsIncludingNested().property("viewModels"))!!
    val matching = index.resolveConsumer(accessor).uniformBindings.orEmpty()

    assertEquals(
      setOf("FooViewModel", "BarViewModel"),
      matching.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertEquals(
      setOf(
        "@test.ViewModelKey(value = test.FooViewModel::class)",
        "@test.ViewModelKey(value = test.BarViewModel::class)",
      ),
      matching.mapTo(mutableSetOf()) { it.mapKeyValue },
    )
  }

  fun testRepeatedMapContributionsKeepUnrelatedTypeAnnotatedKeys() {
    project.setMetroOptions("custom-into-map" to "test/PrioritizedMapContribution")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        @Repeatable
        annotation class PrioritizedMapContribution(
          val scope: KClass<*>,
          val binding: binding<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject
        @PrioritizedMapContribution(
          AppScope::class,
          binding = binding<@StringKey("shared") Service>(),
          priority = 10,
        )
        @PrioritizedMapContribution(
          AppScope::class,
          binding = binding<@StringKey("retained") Service>(),
          priority = 100,
        )
        class SharedService : Service

        @Inject
        @PrioritizedMapContribution(
          AppScope::class,
          binding = binding<@StringKey("shared") Service>(),
          priority = 50,
        )
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Map<String, Service>
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val accessor = index.consumerEntryAt(declarations.property("services"))!!
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(graph).single())!!

    assertEquals(
      setOf("HigherService", "SharedService"),
      index.bindingsFor(accessor, queryContext).mapTo(mutableSetOf()) { it.implementationName },
    )
    assertEquals(
      setOf("HigherService", "SharedService"),
      index.contributionsFor(queryContext).mapNotNullTo(mutableSetOf()) {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testEqualContributionPrioritiesKeepAllBindings() {
    project.setMetroOptions("custom-contributes-binding" to "test/PrioritizedBinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedBinding(val scope: KClass<*>, val priority: Int)

        interface Service

        @Inject @PrioritizedBinding(AppScope::class, priority = 100)
        class FirstService : Service

        @Inject @PrioritizedBinding(AppScope::class, priority = 100)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      setOf("FirstService", "SecondService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testAnvilRanksIgnoreEnumPriorityArgument() {
    project.setMetroOptions(
      "custom-contributes-binding" to "test/RankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        enum class LegacyPriority { NORMAL }

        annotation class RankedBinding(
          val scope: KClass<*>,
          val priority: LegacyPriority = LegacyPriority.NORMAL,
          val rank: Int,
        )

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 50)
        class LowerService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      listOf("HigherService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().map { it.implementationName },
    )
  }

  fun testAnvilRanksReplaceLowerRankedContributions() {
    project.setMetroOptions(
      "custom-contributes-binding" to "test/RankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 50)
        class LowerService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val accessor = index.consumerEntryAt(declarations.property("service"))!!
    val graph = index.graphEntryAt(declarations.klass("AppGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(graph).single())!!

    assertEquals(
      listOf("HigherService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(
      listOf("HigherService"),
      index.contributionsFor(queryContext).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testAnvilRanksDoNotReplaceContributionsFromParentScopes() {
    project.setMetroOptions(
      "custom-contributes-binding" to "test/RankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        abstract class ChildScope

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class ParentService : Service

        @Inject @RankedBinding(ChildScope::class, rank = 50)
        class ChildService : Service

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          val service: Service
        }

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val accessor = index.consumerEntryAt(declarations.property("service"))!!
    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    val queryContext = index.queryContext(index.contextsFor(child).single())!!

    assertEquals(
      setOf("ParentService", "ChildService"),
      index.bindingsFor(accessor, queryContext).mapTo(mutableSetOf()) { it.implementationName },
    )
    assertEquals(
      listOf("ChildService"),
      index.contributionsFor(queryContext).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertEquals(
      listOf("ParentService"),
      index.inheritedContributionsFor(queryContext).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }

  fun testEqualAnvilRanksKeepAllContributions() {
    project.setMetroOptions(
      "custom-contributes-binding" to "test/RankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class FirstService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      setOf("FirstService", "SecondService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testAnvilRanksDoNotApplyWithoutInterop() {
    project.setMetroOptions("custom-contributes-binding" to "test/RankedBinding")
    val file =
      myFixture.configureMetroFile(
        """
        import kotlin.reflect.KClass

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 50)
        class LowerService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val accessor = index.consumerEntryAt(file.declarationsIncludingNested().property("service"))!!

    assertEquals(
      setOf("LowerService", "HigherService"),
      index.resolveConsumer(accessor).uniformBindings.orEmpty().mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
  }

  fun testConsumerResolutionDistinguishesUniformAndContextDependentBindings() {
    val file =
      myFixture.configureMetroFile(
        """
        abstract class OtherScope

        interface PartialRepo
        interface DifferentRepo
        interface StableRepo
        interface MissingRepo

        @Inject
        @ContributesBinding(AppScope::class)
        class PartialAppRepo : PartialRepo

        @Inject
        @ContributesBinding(AppScope::class)
        class DifferentAppRepo : DifferentRepo

        @Inject
        @ContributesBinding(OtherScope::class)
        class DifferentOtherRepo : DifferentRepo

        @Inject class StableRepoImpl : StableRepo

        @BindingContainer
        interface StableBindings {
          @Binds fun bindStable(impl: StableRepoImpl): StableRepo
        }

        @Inject
        class Consumer(
          val partialRepo: PartialRepo,
          val differentRepo: DifferentRepo,
          val stableRepo: StableRepo,
          val missingRepo: MissingRepo,
        )

        @DependencyGraph(AppScope::class, bindingContainers = [StableBindings::class])
        interface AppGraph {
          val consumer: Consumer
        }

        @DependencyGraph(OtherScope::class, bindingContainers = [StableBindings::class])
        interface OtherGraph {
          val consumer: Consumer
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    fun resolution(parameterName: String): ConsumerResolution {
      return index.resolveConsumer(index.consumerEntryAt(declarations.parameter(parameterName))!!)
    }

    fun implementationsByGraph(resolution: ConsumerResolution): Map<String?, List<String?>> {
      return resolution.perContext.entries.associate { (context, bindings) ->
        context.graph.name to bindings.map { it.implementationName }
      }
    }

    val partial = resolution("partialRepo")
    assertNull(partial.uniformBindings)
    assertEquals(
      listOf("PartialAppRepo"),
      partial.candidateBindings.map { it.implementationName },
    )
    assertEquals(setOf("OtherGraph"), partial.emptyContexts.mapTo(mutableSetOf()) { it.graph.name })
    assertEquals(
      mapOf(
        "AppGraph" to listOf("PartialAppRepo"),
        "OtherGraph" to emptyList(),
      ),
      implementationsByGraph(partial),
    )

    val different = resolution("differentRepo")
    assertNull(different.uniformBindings)
    assertEquals(
      setOf("DifferentAppRepo", "DifferentOtherRepo"),
      different.candidateBindings.mapTo(mutableSetOf()) { it.implementationName },
    )
    assertTrue(different.emptyContexts.isEmpty())

    val differentConsumer =
      checkNotNull(index.consumerEntryAt(declarations.parameter("differentRepo")))
    val appBindings =
      index.bindingEntriesAt(declarations.klass("DifferentAppRepo")).filter {
        it.typeKey.renderedType == "test.DifferentRepo"
      }
    val contextsByGraph = different.perContext.keys.associateBy { it.graph.name }
    assertTrue(
      index
        .consumersFor(appBindings, contextsByGraph.getValue("AppGraph").path)
        .contains(differentConsumer)
    )
    assertFalse(
      index
        .consumersFor(appBindings, contextsByGraph.getValue("OtherGraph").path)
        .contains(differentConsumer)
    )

    val stable = resolution("stableRepo")
    assertEquals(
      listOf("StableRepoImpl"),
      stable.uniformBindings.orEmpty().map { it.implementationName },
    )
    assertEquals(2, stable.perContext.size)
    assertTrue(stable.emptyContexts.isEmpty())

    val missing = resolution("missingRepo")
    assertTrue(missing.uniformBindings.orEmpty().isEmpty())
    assertTrue(missing.candidateBindings.isEmpty())
    assertEquals(2, missing.perContext.size)
    assertEquals(2, missing.emptyContexts.size)
  }

  fun testGraphExtensionParentsOnlyComeFromExtensionCreationAccessors() {
    val file =
      myFixture.configureByText(
        "ExtensionParents.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.ContributesBinding
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.GraphExtension
        import dev.zacsweers.metro.Inject

        abstract class ChildScope

        interface Thing

        @ContributesBinding(AppScope::class)
        @Inject
        class RealThing : Thing

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          class Token
          val thing: Thing
        }

        @DependencyGraph(AppScope::class)
        interface ParentGraph {
          val token: ChildGraph.Token
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()

    val child = index.graphEntryAt(declarations.klass("ChildGraph"))!!
    val childContexts = index.contextsFor(child)
    assertEquals(1, childContexts.size)
    assertEquals(1, childContexts.single().chain.size)

    val thing = index.consumerEntryAt(declarations.property("thing"))!!
    assertTrue(index.resolveConsumer(thing).uniformBindings.orEmpty().isEmpty())
  }

  fun testLibraryContributionHintsCanContributeSameProviderToMultipleScopes() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureByText(
          "LibMultiScopeHints.kt",
          """
          package test

          import dev.zacsweers.metro.AppScope
          import dev.zacsweers.metro.DependencyGraph
          import libtest.LibDual
          import libtest.LibScope

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val appDual: LibDual
          }

          @DependencyGraph(LibScope::class)
          interface LibGraph {
            val libDual: LibDual
          }
          """
            .trimIndent(),
        ) as KtFile
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val declarations = file.declarationsIncludingNested()

      val appContext =
        index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
      val appDual = index.consumerEntryAt(declarations.property("appDual"))!!
      assertEquals(
        listOf("LibDualImpl"),
        index.bindingsFor(appDual, index.queryContext(appContext)!!).map {
          it.implementationName
        },
      )

      val libContext =
        index.contextsFor(index.graphEntryAt(declarations.klass("LibGraph"))!!).single()
      val libDual = index.consumerEntryAt(declarations.property("libDual"))!!
      assertEquals(
        listOf("LibDualImpl"),
        index.bindingsFor(libDual, index.queryContext(libContext)!!).map {
          it.implementationName
        },
      )
    }
  }

  fun testScopedBindingsRequireMatchingGraphScope() {
    val file =
      myFixture.configureByText(
        "Scoped.kt",
        """
        package test

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject
        import dev.zacsweers.metro.SingleIn

        abstract class OtherScope

        @SingleIn(AppScope::class)
        @Inject
        class Repo

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val appRepo: Repo
        }

        @DependencyGraph(OtherScope::class)
        interface OtherGraph {
          val otherRepo: Repo
        }

        @SingleIn(AppScope::class)
        @DependencyGraph
        interface ExplicitGraph {
          val explicitRepo: Repo
        }
        """
          .trimIndent(),
      ) as KtFile
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val declarations = file.declarationsIncludingNested()
    val consumer = index.consumerEntryAt(declarations.property("appRepo"))!!

    // @DependencyGraph(AppScope::class) implicitly conveys @SingleIn(AppScope::class)
    val appContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("AppGraph"))!!).single()
    assertEquals(
      listOf("injected class"),
      index.bindingsFor(consumer, index.queryContext(appContext)!!).map { it.label },
    )

    // A graph with a different scope is not a home for this binding
    val otherContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("OtherGraph"))!!).single()
    assertTrue(index.bindingsFor(consumer, index.queryContext(otherContext)!!).isEmpty())

    // Explicitly declared scope annotations on the graph also count
    val explicitContext =
      index.contextsFor(index.graphEntryAt(declarations.klass("ExplicitGraph"))!!).single()
    assertEquals(
      listOf("injected class"),
      index.bindingsFor(consumer, index.queryContext(explicitContext)!!).map { it.label },
    )
  }

  fun testIndexIsEmptyWhenMetroDisabled() {
    project.setMetroOptions("enabled" to "false")
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    assertTrue(index.bindings.isEmpty())
    assertTrue(index.consumers.isEmpty())
    assertTrue(index.graphs.isEmpty())
  }
}
