// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.WaitFor
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.unused.MetroImplicitUsageCache
import dev.zacsweers.metro.idea.unused.MetroUnusedDeclarationInspectionSuppressor
import dev.zacsweers.metro.idea.unused.isMetroImplicitUsage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UnusedSymbolInspection
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile

class MetroImplicitUsageProviderTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    setMetroEnabled(null)
    module.addMetroRuntimeLibrary()
  }

  fun testMarksMetroDeclarationsAsImplicitlyUsed() {
    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.function("bindService").isMetroImplicitUsage())
    assertTrue(declarations.function("provideService").isMetroImplicitUsage())
    assertTrue(declarations.property("providedProperty").isMetroImplicitUsage())
    assertTrue(declarations.property("getterProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.function("multibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.parameter("providedInstance").isMetroImplicitUsage())
    assertTrue(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("InjectedService").primaryConstructor!!.isMetroImplicitUsage())
    assertTrue(declarations.function("functionInject").isMetroImplicitUsage())
    assertTrue(declarations.klass("AssistedInjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedMapService").isMetroImplicitUsage())
    assertTrue(declarations.obj("ContributedObjectService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedGraphInterface").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedBindingContainer").isMetroImplicitUsage())
    assertTrue(declarations.obj("ContributedObjectContainer").isMetroImplicitUsage())
    assertTrue(
      declarations
        .klass("ConstructorAssistedInjectedService")
        .primaryConstructor!!
        .isMetroImplicitUsage()
    )
  }

  fun testDoesNotMarkMetroDeclarationsWhenPluginIsNotConfigured() {
    project.clearMetroOptions()
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("provideService").isMetroImplicitUsage())
    assertFalse(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertFalse(declarations.klass("ContributedGraphInterface").isMetroImplicitUsage())
  }

  fun testProductionEdtWarmsColdMetroStateInBackground() {
    val declaration = kotlinFileDeclarations().function("bindService")
    project.clearMetroOptions()
    project.setMetroOptions()

    assertFalse(productionEdtImplicitUsage(declaration))
    awaitCachedAnswer(declaration, expected = true)
    assertTrue(productionEdtImplicitUsage(declaration))
  }

  fun testProductionEdtCacheUsesExactAnnotationResolutionForWholeFile() {
    myFixture.addFileToProject(
      "other/Inject.kt",
      """
      package other

      annotation class Inject
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "ExactAnnotations.kt",
        """
        package test

        import dev.zacsweers.metro.Provides
        import dev.zacsweers.metro.Inject as MetroInject
        import other.Inject

        interface Module {
          @Provides fun provideValue(): String = "value"
        }

        class AliasedInjectedType @MetroInject constructor()
        class FullyQualifiedInjectedType @dev.zacsweers.metro.Inject constructor()
        class UnrelatedInjectedType @Inject constructor()
        """
          .trimIndent(),
      ) as KtFile
    val declarations = file.declarationsIncludingNested()
    val provider = declarations.function("provideValue")
    val aliasedType = declarations.klass("AliasedInjectedType")
    val fullyQualifiedType = declarations.klass("FullyQualifiedInjectedType")
    val unrelatedType = declarations.klass("UnrelatedInjectedType")

    assertFalse(productionEdtImplicitUsage(provider))
    awaitCachedAnswer(provider, expected = true)

    assertTrue(productionEdtImplicitUsage(provider))
    assertTrue(productionEdtImplicitUsage(aliasedType))
    assertTrue(productionEdtImplicitUsage(fullyQualifiedType))
    assertEquals(false, project.service<MetroImplicitUsageCache>().cachedAnswer(unrelatedType))
    assertFalse(productionEdtImplicitUsage(unrelatedType))
  }

  fun testProductionEdtCacheInvalidatesForPsiChanges() {
    val file = configureMetroFile()
    val declaration = file.declarationsIncludingNested().function("bindService")
    val cache = project.service<MetroImplicitUsageCache>()
    assertFalse(productionEdtImplicitUsage(declaration))
    awaitCachedAnswer(declaration, expected = true)

    myFixture.editor.caretModel.moveToOffset(file.textLength)
    myFixture.type("\n")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val updatedDeclaration = file.declarationsIncludingNested().function("bindService")

    assertNull(cache.cachedAnswer(updatedDeclaration))
    assertFalse(productionEdtImplicitUsage(updatedDeclaration))
    awaitCachedAnswer(updatedDeclaration, expected = true)
    assertTrue(productionEdtImplicitUsage(updatedDeclaration))
  }

  fun testProductionEdtCacheInvalidatesForRootChanges() {
    val declaration = kotlinFileDeclarations().function("bindService")
    val cache = project.service<MetroImplicitUsageCache>()
    assertFalse(productionEdtImplicitUsage(declaration))
    awaitCachedAnswer(declaration, expected = true)

    val additionalRoot = myFixture.tempDirFixture.findOrCreateDir("additional-root")
    ModuleRootModificationUtil.updateModel(module) { model ->
      model.contentEntries.single().addSourceFolder(additionalRoot, false)
    }

    assertNull(cache.cachedAnswer(declaration))
    assertFalse(productionEdtImplicitUsage(declaration))
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    awaitCachedAnswer(declaration, expected = true)
    assertTrue(productionEdtImplicitUsage(declaration))
  }

  fun testProductionEdtCacheInvalidatesForCompilerSettingsChanges() {
    project.setMetroOptions("custom-inject" to "test/CustomInject")
    val declaration = kotlinFileDeclarations().klass("CustomInjectedService")
    val cache = project.service<MetroImplicitUsageCache>()
    assertFalse(productionEdtImplicitUsage(declaration))
    awaitCachedAnswer(declaration, expected = true)

    project.setMetroOptions()

    assertNull(cache.cachedAnswer(declaration))
    assertFalse(productionEdtImplicitUsage(declaration))
    awaitCachedAnswer(declaration, expected = false)
    assertFalse(productionEdtImplicitUsage(declaration))
  }

  fun testProductionEdtCacheWaitsForSmartModeBeforePublishing() {
    val declaration = kotlinFileDeclarations().function("bindService")
    project.setMetroOptions()
    val cache = project.service<MetroImplicitUsageCache>()
    val workerStarted = CompletableFuture<Unit>()
    cache.setComputationStartObserver { workerStarted.complete(Unit) }

    try {
      DumbModeTestUtils.runInDumbModeSynchronously(project) {
        assertFalse(productionEdtImplicitUsage(declaration))
        PlatformTestUtil.waitForFuture(workerStarted, 30_000)
        assertNull(cache.cachedAnswer(declaration))
      }

      // Returning to smart mode can change the queued inputs. The next highlighting pass retries.
      productionEdtImplicitUsage(declaration)
      awaitCachedAnswer(declaration, expected = true)
      assertTrue(productionEdtImplicitUsage(declaration))
    } finally {
      cache.setComputationStartObserver(null)
    }
  }

  fun testImplicitUsageWorkersAreBoundedAndCoalesceFileDemand() {
    val declarations = implicitUsageDeclarations(6)
    withImplicitUsageCache { cache, _ ->
      val starts = AtomicInteger()
      val startsByFile = ConcurrentHashMap<VirtualFile, AtomicInteger>()
      val workersStarted = CompletableFuture<Unit>()
      val releaseWorkers = CountDownLatch(1)
      cache.setComputationStartObserver { file ->
        startsByFile.computeIfAbsent(file) { AtomicInteger() }.incrementAndGet()
        if (starts.incrementAndGet() == 2) workersStarted.complete(Unit)
        check(releaseWorkers.await(30, TimeUnit.SECONDS))
      }
      try {
        for (declaration in declarations) {
          repeat(5) { assertFalse(cache.answerOrSchedule(declaration)) }
        }
        PlatformTestUtil.waitForFuture(workersStarted, 30_000)

        assertEquals(2, cache.activeWorkerCount())
        assertEquals(2, starts.get())
        assertEquals(
          declarations.drop(2).map { it.containingKtFile.virtualFile },
          cache.queuedFiles(),
        )

        releaseWorkers.countDown()
        for (declaration in declarations) awaitCachedAnswer(declaration, expected = true, cache)
        awaitImplicitUsageIdle(cache)
        assertEquals(declarations.size, starts.get())
        assertTrue(startsByFile.values.all { it.get() == 1 })
      } finally {
        releaseWorkers.countDown()
      }
    }
  }

  fun testImplicitUsageQueueOverflowRestartsAnAbandonedBatchAndCanBeRequestedAgain() {
    val declarations = implicitUsageDeclarations(35)
    withImplicitUsageCache { cache, _ ->
      val starts = AtomicInteger()
      val workersStarted = CompletableFuture<Unit>()
      val releaseWorkers = CountDownLatch(1)
      val restarts = AtomicInteger()
      val restarted = CompletableFuture<Unit>()
      cache.setComputationStartObserver {
        if (starts.incrementAndGet() == 2) workersStarted.complete(Unit)
        check(releaseWorkers.await(30, TimeUnit.SECONDS))
        throw ProcessCanceledException()
      }
      cache.setRestartObserver {
        restarts.incrementAndGet()
        restarted.complete(Unit)
      }
      try {
        declarations.forEach { assertFalse(cache.answerOrSchedule(it)) }
        PlatformTestUtil.waitForFuture(workersStarted, 30_000)

        val evicted = declarations[2]
        assertEquals(
          declarations.drop(3).map { it.containingKtFile.virtualFile },
          cache.queuedFiles(),
        )
        assertEquals(2, cache.activeWorkerCount())

        releaseWorkers.countDown()
        PlatformTestUtil.waitForFuture(restarted, 30_000)
        awaitImplicitUsageIdle(cache)
        assertEquals(34, starts.get())
        assertEquals(1, restarts.get())
        assertNull(cache.cachedAnswer(evicted))

        cache.setComputationStartObserver(null)
        assertFalse(cache.answerOrSchedule(evicted))
        awaitCachedAnswer(evicted, expected = true, cache)
      } finally {
        releaseWorkers.countDown()
      }
    }
  }

  fun testFreshImplicitUsageDemandSurvivesThePreviousSnapshotWorker() {
    val declaration = implicitUsageDeclarations(1).single()
    withImplicitUsageCache { cache, _ ->
      val starts = AtomicInteger()
      val firstStarted = CompletableFuture<Unit>()
      val releaseFirst = CountDownLatch(1)
      cache.setComputationStartObserver {
        if (starts.incrementAndGet() == 1) {
          firstStarted.complete(Unit)
          check(releaseFirst.await(30, TimeUnit.SECONDS))
        }
      }
      try {
        assertFalse(cache.answerOrSchedule(declaration))
        PlatformTestUtil.waitForFuture(firstStarted, 30_000)
        project.setMetroOptions("enabled" to "false")
        repeat(5) { assertFalse(cache.answerOrSchedule(declaration)) }
        assertEquals(listOf(declaration.containingKtFile.virtualFile), cache.queuedFiles())
        assertEquals(1, cache.activeWorkerCount())

        releaseFirst.countDown()
        awaitCachedAnswer(declaration, expected = false, cache)
        awaitImplicitUsageIdle(cache)
        assertEquals(2, starts.get())
      } finally {
        releaseFirst.countDown()
      }
    }
  }

  fun testFailedImplicitUsageWorkerReleasesItsSlotAndAllowsRetry() {
    val declaration = implicitUsageDeclarations(1).single()
    withImplicitUsageCache { cache, workerScope ->
      val failOnce = AtomicBoolean(true)
      val failureStarted = CompletableFuture<Unit>()
      cache.setComputationStartObserver {
        if (failOnce.compareAndSet(true, false)) {
          failureStarted.complete(Unit)
          throw IllegalStateException("Expected implicit usage worker failure")
        }
      }
      assertFalse(cache.answerOrSchedule(declaration))
      PlatformTestUtil.waitForFuture(failureStarted, 30_000)
      awaitImplicitUsageIdle(cache)
      assertTrue(workerScope.isActive)
      assertNull(cache.cachedAnswer(declaration))

      assertFalse(cache.answerOrSchedule(declaration))
      awaitCachedAnswer(declaration, expected = true, cache)
    }
  }

  fun testImplicitUsageCancellationBeforeStartReleasesItsSlot() {
    val declarations = implicitUsageDeclarations(3)
    val executor = Executors.newSingleThreadExecutor()
    val dispatcher = executor.asCoroutineDispatcher()
    val workerScope = CoroutineScope(SupervisorJob() + dispatcher)
    val dispatcherPaused = CompletableFuture<Unit>()
    val releaseDispatcher = CountDownLatch(1)
    executor.submit {
      dispatcherPaused.complete(Unit)
      check(releaseDispatcher.await(30, TimeUnit.SECONDS))
    }
    try {
      PlatformTestUtil.waitForFuture(dispatcherPaused, 30_000)
      withImplicitUsageCache(workerScope) { cache, _ ->
        val startedFiles = ConcurrentHashMap.newKeySet<VirtualFile>()
        cache.setComputationStartObserver { startedFiles += it }
        try {
          assertFalse(cache.answerOrSchedule(declarations[0]))
          val canceledWorker = workerScope.coroutineContext.job.children.single()
          assertFalse(cache.answerOrSchedule(declarations[1]))
          assertFalse(cache.answerOrSchedule(declarations[2]))
          canceledWorker.cancel()
          releaseDispatcher.countDown()

          canceledWorker.awaitTestCompletion()
          for (declaration in declarations.drop(1)) {
            awaitCachedAnswer(declaration, expected = true, cache)
          }
          awaitImplicitUsageIdle(cache)
          val canceledFile = declarations[0].containingKtFile.virtualFile
          assertFalse(canceledFile in startedFiles)
          assertNull(cache.cachedAnswer(declarations[0]))

          assertFalse(cache.answerOrSchedule(declarations[0]))
          awaitCachedAnswer(declarations[0], expected = true, cache)
        } finally {
          releaseDispatcher.countDown()
        }
      }
    } finally {
      releaseDispatcher.countDown()
      workerScope.cancel()
      dispatcher.close()
    }
  }

  fun testImplicitUsageDisposalAndScopeCancellationDropQueuedDemand() {
    val declarations = implicitUsageDeclarations(3)
    for (cancelScope in listOf(false, true)) {
      withImplicitUsageCache { cache, workerScope ->
        val starts = AtomicInteger()
        val workersStarted = CompletableFuture<Unit>()
        val releaseWorkers = CountDownLatch(1)
        cache.setComputationStartObserver {
          if (starts.incrementAndGet() == 2) workersStarted.complete(Unit)
          check(releaseWorkers.await(30, TimeUnit.SECONDS))
        }
        try {
          declarations.forEach { assertFalse(cache.answerOrSchedule(it)) }
          PlatformTestUtil.waitForFuture(workersStarted, 30_000)
          val workers = workerScope.coroutineContext.job.children.toList()
          if (cancelScope) workerScope.cancel() else Disposer.dispose(cache)
          releaseWorkers.countDown()
          workers.forEach { it.awaitTestCompletion() }
          awaitImplicitUsageIdle(cache)

          assertEquals(2, starts.get())
          assertFalse(cache.answerOrSchedule(declarations.last()))
          assertNull(cache.cachedAnswer(declarations.last()))
          assertEquals(0, cache.activeWorkerCount())
          assertTrue(cache.queuedFiles().isEmpty())
        } finally {
          releaseWorkers.countDown()
        }
      }
    }
  }

  fun testMarksCustomMetroDeclarationsAsImplicitlyUsedWhenConfigured() {
    project.setMetroOptions(
      "custom-binds" to "test/CustomBinds",
      "custom-contributes-binding" to "test/CustomContributesBinding",
      "custom-contributes-into-set" to "test/CustomContributesIntoCollection",
      "custom-elements-into-set" to "test/CustomContributesIntoSet",
      "custom-provides" to "test/CustomProvides",
      "custom-into-map" to "test/CustomContributesIntoMap",
      "custom-multibinds" to "test/CustomMultibinds",
      "custom-inject" to "test/CustomInject",
      "custom-assisted-inject" to "test/CustomAssistedInject",
    )

    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.function("customBindService").isMetroImplicitUsage())
    assertTrue(declarations.function("customProvideService").isMetroImplicitUsage())
    assertTrue(declarations.property("customProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.property("customGetterProvidedProperty").isMetroImplicitUsage())
    assertTrue(declarations.function("customMultibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.parameter("customProvidedInstance").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomInjectedService").isMetroImplicitUsage())
    assertTrue(
      declarations.klass("CustomInjectedService").primaryConstructor!!.isMetroImplicitUsage()
    )
    assertTrue(declarations.function("customFunctionInject").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomAssistedInjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedMapService").isMetroImplicitUsage())
    assertTrue(declarations.klass("CustomContributedCollectionService").isMetroImplicitUsage())
    assertTrue(
      declarations
        .klass("CustomConstructorAssistedInjectedService")
        .primaryConstructor!!
        .isMetroImplicitUsage()
    )
  }

  fun testDoesNotMarkCustomMetroDeclarationsAsImplicitlyUsedWithoutOptions() {
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("customBindService").isMetroImplicitUsage())
    assertFalse(declarations.function("customProvideService").isMetroImplicitUsage())
    assertFalse(declarations.function("customMultibindsServices").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomInjectedService").isMetroImplicitUsage())
    assertFalse(declarations.function("customFunctionInject").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomAssistedInjectedService").isMetroImplicitUsage())
    assertFalse(declarations.klass("CustomContributedBindingService").isMetroImplicitUsage())
  }

  fun testNestedCustomAnnotationsKeepTheirClassIdentity() {
    project.setMetroOptions("custom-inject" to "test/Di.Inject")
    val file =
      myFixture.configureByText(
        "NestedAnnotations.kt",
        """
        package test

        class Di {
          annotation class Inject
        }

        class Other {
          annotation class Inject
        }

        class InjectedService @Di.Inject constructor()
        class UnrelatedService @Other.Inject constructor()
        """
          .trimIndent(),
      ) as KtFile
    val declarations = file.declarationsIncludingNested()

    assertTrue(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertTrue(declarations.klass("InjectedService").primaryConstructor!!.isMetroImplicitUsage())
    assertFalse(declarations.klass("UnrelatedService").isMetroImplicitUsage())
  }

  fun testContributedTypesAreUsedWithoutContributionProviderGeneration() {
    project.setMetroOptions(
      "contributes-as-inject" to "false",
      "generate-contribution-providers" to "false",
    )
    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.klass("ContributedGraphInterface").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedBindingContainer").isMetroImplicitUsage())
    assertTrue(declarations.obj("ContributedObjectContainer").isMetroImplicitUsage())
    assertFalse(declarations.klass("UnrelatedType").isMetroImplicitUsage())
  }

  fun testContributesToRequiresExactAnnotationIdentity() {
    myFixture.addFileToProject(
      "other/ContributesTo.kt",
      """
      package other

      import kotlin.reflect.KClass

      annotation class ContributesTo(val scope: KClass<*>)
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "ContributedTypes.kt",
        """
        package test

        import dev.zacsweers.metro.ContributesTo as MetroContributesTo
        import other.ContributesTo

        object AppScope
        @MetroContributesTo(AppScope::class) interface AliasedContribution
        @dev.zacsweers.metro.ContributesTo(AppScope::class) interface QualifiedContribution
        @ContributesTo(AppScope::class) interface UnrelatedContribution
        """
          .trimIndent(),
      ) as KtFile
    val declarations = file.declarationsIncludingNested()

    assertTrue(declarations.klass("AliasedContribution").isMetroImplicitUsage())
    assertTrue(declarations.klass("QualifiedContribution").isMetroImplicitUsage())
    assertFalse(declarations.klass("UnrelatedContribution").isMetroImplicitUsage())
  }

  fun testMarksContributionProviderDeclarationsAsImplicitlyUsedWhenConfigured() {
    project.setMetroOptions(
      "contributes-as-inject" to "false",
      "generate-contribution-providers" to "true",
    )

    val declarations = kotlinFileDeclarations()

    assertTrue(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedSetService").isMetroImplicitUsage())
    assertTrue(declarations.klass("ContributedMapService").isMetroImplicitUsage())
    assertFalse(declarations.klass("ExposedContributedBindingService").isMetroImplicitUsage())
  }

  fun testMarksDaggerInteropDeclarationsAsImplicitlyUsedWhenConfigured() {
    project.setMetroOptions("interop-include-dagger-annotations" to "true")
    myFixture.addFileToProject(
      "dagger/Annotations.kt",
      """
      package dagger

      annotation class Binds
      annotation class BindsInstance
      annotation class Provides
      """
        .trimIndent(),
    )
    myFixture.addFileToProject(
      "dagger/assisted/Annotations.kt",
      """
      package dagger.assisted

      annotation class AssistedInject
      """
        .trimIndent(),
    )
    myFixture.addFileToProject(
      "dagger/multibindings/Annotations.kt",
      """
      package dagger.multibindings

      annotation class Multibinds
      """
        .trimIndent(),
    )

    val file =
      myFixture.configureByText(
        "DaggerTest.kt",
        """
        package test

        import dagger.Binds
        import dagger.Provides
        import dagger.assisted.AssistedInject
        import dagger.multibindings.Multibinds

        interface Service
        class ServiceImpl : Service

        interface DaggerModule {
          @Binds fun daggerBindService(impl: ServiceImpl): Service
          @Provides fun daggerProvideService(): Service = ServiceImpl()
          @Multibinds fun daggerMultibindsServices(): Set<Service>
        }

        class DaggerAssistedInjectedService @AssistedInject constructor(service: Service)
        """
          .trimIndent(),
      ) as KtFile
    val declarations = file.declarationsIncludingNested()

    assertTrue(declarations.function("daggerBindService").isMetroImplicitUsage())
    assertTrue(declarations.function("daggerProvideService").isMetroImplicitUsage())
    assertTrue(declarations.function("daggerMultibindsServices").isMetroImplicitUsage())
    assertTrue(declarations.klass("DaggerAssistedInjectedService").isMetroImplicitUsage())
  }

  fun testMarksCircuitInjectDeclarationsAsImplicitlyUsedWhenEnabled() {
    project.setMetroOptions("enable-circuit-codegen" to "true")

    val declarations = circuitFileDeclarations()

    assertTrue(declarations.function("circuitPresenter").isMetroImplicitUsage())
    assertTrue(declarations.klass("CircuitUiClass").isMetroImplicitUsage())
  }

  fun testDoesNotMarkCircuitInjectDeclarationsAsImplicitlyUsedWithoutOption() {
    val declarations = circuitFileDeclarations()

    assertFalse(declarations.function("circuitPresenter").isMetroImplicitUsage())
    assertFalse(declarations.klass("CircuitUiClass").isMetroImplicitUsage())
  }

  private fun circuitFileDeclarations(): List<KtDeclaration> {
    myFixture.addFileToProject(
      "circuit/CircuitInject.kt",
      """
      package com.slack.circuit.codegen.annotations

      import kotlin.reflect.KClass

      annotation class CircuitInject(val screen: KClass<*>, val scope: KClass<*>)
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureByText(
        "CircuitTest.kt",
        """
        package test

        import com.slack.circuit.codegen.annotations.CircuitInject

        object AppScope
        object HomeScreen

        @CircuitInject(HomeScreen::class, AppScope::class)
        fun circuitPresenter(): Int = 0

        @CircuitInject(HomeScreen::class, AppScope::class)
        class CircuitUiClass
        """
          .trimIndent(),
      ) as KtFile
    return file.declarationsIncludingNested()
  }

  fun testDoesNotMarkUnsupportedDeclarationsAsImplicitlyUsed() {
    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("unusedFunction").isMetroImplicitUsage())
    assertFalse(declarations.klass("ClassAnnotatedInject").isMetroImplicitUsage())
    assertFalse(declarations.property("memberInject").isMetroImplicitUsage())
    assertFalse(declarations.klass("UnrelatedType").isMetroImplicitUsage())
  }

  fun testDoesNotMarkMetroDeclarationsWhenSuppressionSettingIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.suppressUnusedWarnings = false
    try {
      val declarations = kotlinFileDeclarations()

      assertFalse(declarations.function("bindService").isMetroImplicitUsage())
      assertFalse(declarations.klass("InjectedService").isMetroImplicitUsage())
      assertFalse(declarations.klass("ContributedGraphInterface").isMetroImplicitUsage())
      assertFalse(declarations.obj("ContributedObjectContainer").isMetroImplicitUsage())
    } finally {
      settings.suppressUnusedWarnings = true
    }
  }

  fun testDoesNotMarkMetroDeclarationsAsImplicitlyUsedWhenMetroIsDisabled() {
    setMetroEnabled(false)

    val declarations = kotlinFileDeclarations()

    assertFalse(declarations.function("bindService").isMetroImplicitUsage())
    assertFalse(declarations.function("provideService").isMetroImplicitUsage())
    assertFalse(declarations.function("multibindsServices").isMetroImplicitUsage())
    assertFalse(declarations.klass("InjectedService").isMetroImplicitUsage())
    assertFalse(declarations.function("functionInject").isMetroImplicitUsage())
    assertFalse(declarations.klass("ContributedBindingService").isMetroImplicitUsage())
    assertFalse(declarations.klass("ContributedGraphInterface").isMetroImplicitUsage())
    assertFalse(declarations.obj("ContributedObjectContainer").isMetroImplicitUsage())
  }

  fun testUnusedDeclarationSuppressorRespectsMetroEnabledState() {
    val declarations = kotlinFileDeclarations()
    val suppressor = MetroUnusedDeclarationInspectionSuppressor()
    val bindService = declarations.function("bindService")

    assertTrue(suppressor.isSuppressedFor(bindService, "unused"))

    setMetroEnabled(false)

    assertFalse(suppressor.isSuppressedFor(bindService, "unused"))
  }

  fun testUnusedDeclarationSuppressorIgnoresOtherUnusedInspections() {
    val declarations = kotlinFileDeclarations()
    val suppressor = MetroUnusedDeclarationInspectionSuppressor()
    val bindService = declarations.function("bindService")

    assertTrue(suppressor.isSuppressedFor(bindService, "UnusedSymbol"))
    assertFalse(suppressor.isSuppressedFor(bindService, "UnusedImport"))
    assertFalse(suppressor.isSuppressedFor(bindService, "UnusedParameter"))
  }

  fun testUnusedDeclarationHighlightingRespectsMetroImplicitUsage() {
    myFixture.enableInspections(UnusedSymbolInspection())
    configureMetroFile()

    // The highlighting fixture rejects the cancellation used to wait for a cold index.
    project.service<MetroResolutionService>().awaitIndex(myFixture.file)
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    val warningDescriptions = warnings.map { it.description }.toSet()

    assertFalse("bindService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "bindService" is never used""")
    }
    assertFalse("provideService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "provideService" is never used""")
    }
    assertFalse("multibindsServices should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "multibindsServices" is never used""")
    }
    assertFalse("InjectedService should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Class "InjectedService" is never used""")
    }
    assertFalse("functionInject should not be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "functionInject" is never used""")
    }
    assertTrue("unusedFunction should still be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Function "unusedFunction" is never used""")
    }
    for (name in
      listOf(
        "ContributedGraphInterface",
        "ContributedBindingContainer",
        "ContributedObjectContainer",
      )) {
      assertFalse("$name should not be reported as unused:\n$warningText") {
        warnings.any { it.text == name && it.description.orEmpty().contains("is never used") }
      }
    }
    assertTrue("UnrelatedType should still be reported as unused:\n$warningText") {
      warningDescriptions.contains("""Class "UnrelatedType" is never used""")
    }
  }

  fun testUnusedDeclarationHighlightingRespectsSecondaryInjectConstructors() {
    myFixture.enableInspections(UnusedSymbolInspection())
    myFixture.configureByText(
      "Ctors.kt",
      """
      package test

      import dev.zacsweers.metro.Inject

      class Repository(val name: String) {
        @Inject
        constructor(count: Int) : this(count.toString())
      }

      fun useRepository(): Repository = Repository("direct")
      """
        .trimIndent(),
    )

    project.service<MetroResolutionService>().awaitIndex(myFixture.file)
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    assertFalse("Secondary @Inject constructor should not be reported as unused:\n$warningText") {
      warnings.any { it.description.orEmpty().contains("onstructor") }
    }
  }

  fun testUnusedDeclarationHighlightingRespectsCircuitInjectWhenEnabled() {
    project.setMetroOptions("enable-circuit-codegen" to "true")
    myFixture.enableInspections(UnusedSymbolInspection())
    val declarations = circuitFileDeclarations()
    myFixture.configureFromExistingVirtualFile(declarations.first().containingFile.virtualFile)

    project.service<MetroResolutionService>().awaitIndex(myFixture.file)
    val warnings = myFixture.doHighlighting(HighlightSeverity.WARNING)
    val warningText = warnings.joinToString("\n") { "${it.text}: ${it.description}" }
    val descriptions = warnings.map { it.description }.toSet()

    assertFalse("circuitPresenter should not be reported as unused:\n$warningText") {
      descriptions.contains("""Function "circuitPresenter" is never used""")
    }
    assertFalse("CircuitUiClass should not be reported as unused:\n$warningText") {
      descriptions.contains("""Class "CircuitUiClass" is never used""")
    }
  }

  private fun kotlinFileDeclarations(): List<KtDeclaration> {
    return configureMetroFile().declarationsIncludingNested()
  }

  private fun configureMetroFile(): KtFile {
    return myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.BindingContainer
      import dev.zacsweers.metro.ContributesTo
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.Multibinds
      import dev.zacsweers.metro.Provides
      import dev.zacsweers.metro.AssistedInject
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.ContributesIntoMap
      import dev.zacsweers.metro.ContributesIntoSet
      import dev.zacsweers.metro.ExperimentalMetroApi
      import dev.zacsweers.metro.ExposeImplBinding

      annotation class CustomAssistedInject
      annotation class CustomBinds
      annotation class CustomContributesBinding
      annotation class CustomContributesIntoCollection
      annotation class CustomContributesIntoMap
      annotation class CustomContributesIntoSet
      annotation class CustomInject
      annotation class CustomMultibinds
      annotation class CustomProvides
      object AppScope
      interface Service
      class ServiceImpl : Service

      interface Module {
        @Binds fun bindService(impl: ServiceImpl): Service
        @Provides fun provideService(): Service = ServiceImpl()
        @Provides val providedProperty: Service get() = ServiceImpl()
        val getterProvidedProperty: Service
          @Provides get() = ServiceImpl()
        @Multibinds fun multibindsServices(): Set<Service>
      }

      interface Factory {
        fun create(@Provides providedInstance: Service): Service
      }

      class InjectedService @Inject constructor(service: Service)
      @AssistedInject class AssistedInjectedService(service: Service)
      class ConstructorAssistedInjectedService @AssistedInject constructor(service: Service)
      @ContributesBinding(AppScope::class) class ContributedBindingService : Service
      @ContributesIntoSet(AppScope::class) class ContributedSetService : Service
      @ContributesIntoMap(AppScope::class) class ContributedMapService : Service
      @ContributesBinding(AppScope::class) object ContributedObjectService : Service
      @ContributesTo(AppScope::class) interface ContributedGraphInterface
      @BindingContainer
      @ContributesTo(AppScope::class) class ContributedBindingContainer
      @BindingContainer
      @ContributesTo(AppScope::class) object ContributedObjectContainer
      class UnrelatedType
      @OptIn(ExperimentalMetroApi::class)
      @ExposeImplBinding
      @ContributesBinding(AppScope::class)
      class ExposedContributedBindingService : Service

      @Inject class ClassAnnotatedInject(service: Service)

      class MemberInjectedService {
        @Inject lateinit var memberInject: Service
        @Inject fun functionInject(service: Service) = Unit
      }

      interface CustomModule {
        @CustomBinds fun customBindService(impl: ServiceImpl): Service
        @CustomProvides fun customProvideService(): Service = ServiceImpl()
        @CustomProvides val customProvidedProperty: Service get() = ServiceImpl()
        val customGetterProvidedProperty: Service
          @CustomProvides get() = ServiceImpl()
        @CustomMultibinds fun customMultibindsServices(): Set<Service>
      }

      interface CustomFactory {
        fun create(@CustomProvides customProvidedInstance: Service): Service
      }

      class CustomInjectedService @CustomInject constructor(service: Service)
      @CustomAssistedInject class CustomAssistedInjectedService(service: Service)
      class CustomConstructorAssistedInjectedService @CustomAssistedInject constructor(
        service: Service
      )
      @CustomContributesBinding class CustomContributedBindingService : Service
      @CustomContributesIntoSet class CustomContributedSetService : Service
      @CustomContributesIntoMap class CustomContributedMapService : Service
      @CustomContributesIntoCollection class CustomContributedCollectionService : Service

      class CustomMemberInjectedService {
        @CustomInject fun customFunctionInject(service: Service) = Unit
      }

      fun unusedFunction() = Unit
      """
        .trimIndent(),
    ) as KtFile
  }

  private fun setMetroEnabled(enabled: Boolean?) {
    if (enabled == null) {
      project.setMetroOptions()
    } else {
      project.setMetroOptions("enabled" to enabled.toString())
    }
  }

  private fun productionEdtImplicitUsage(declaration: KtDeclaration): Boolean {
    var result = false
    runInEdtAndWait {
      result = declaration.isMetroImplicitUsage(allowResolutionOnEdt = false)
    }
    return result
  }

  private fun awaitCachedAnswer(
    declaration: KtDeclaration,
    expected: Boolean,
    cache: MetroImplicitUsageCache = project.service(),
  ) {
    object : WaitFor(30_000) {
        override fun condition(): Boolean = cache.cachedAnswer(declaration) == expected
      }
      .assertCompleted("The production EDT check should publish an exact background answer")
  }

  private fun implicitUsageDeclarations(count: Int): List<KtDeclaration> {
    val declarations =
      List(count) { index ->
        val name = "ImplicitUsage$index"
        val file =
          myFixture.addFileToProject(
            "implicit/$name.kt",
            "package test\n\nclass $name @dev.zacsweers.metro.Inject constructor()",
          ) as KtFile
        file.declarations.single()
      }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(project)
    return declarations
  }

  /** Gives each scheduling test its own worker lifetime and waits for cancellation cleanup. */
  private inline fun withImplicitUsageCache(
    workerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    block: (MetroImplicitUsageCache, CoroutineScope) -> Unit,
  ) {
    val cache = MetroImplicitUsageCache(project, workerScope)
    try {
      block(cache, workerScope)
    } finally {
      Disposer.dispose(cache)
      workerScope.cancel()
      workerScope.coroutineContext.job.awaitTestCompletion()
    }
  }

  private fun awaitImplicitUsageIdle(cache: MetroImplicitUsageCache) {
    object : WaitFor(30_000) {
        override fun condition(): Boolean =
          cache.activeWorkerCount() == 0 && cache.queuedFiles().isEmpty()
      }
      .assertCompleted("Implicit usage workers should release their slots and drain the queue")
  }
}
