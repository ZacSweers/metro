// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.graph.WrappedType
import dev.zacsweers.metro.idea.explanation.metroBindingExplanations
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.ConsumerOwnershipBundle
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.sourcePointerIdentity
import dev.zacsweers.metro.idea.navigation.metroEditorTargets
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModuleProvider
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.facet.initializeIfNeeded
import org.jetbrains.kotlin.idea.serialization.updateCompilerArguments
import org.jetbrains.kotlin.idea.workspaceModel.KotlinFacetBridgeFactory
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtObjectDeclaration

/** Exercises module-sensitive resolution with real source modules and Analysis API module data. */
class MetroMultiModuleResolutionTest : UsefulTestCase() {

  private lateinit var fixture: CodeInsightTestFixture

  override fun setUp() {
    super.setUp()
    val factory = IdeaTestFixtureFactory.getFixtureFactory()
    val projectBuilder = factory.createFixtureBuilder(name)
    fixture = factory.createCodeInsightFixture(projectBuilder.fixture)
    val appBuilder = projectBuilder.addModule(EmptyModuleFixtureBuilder::class.java)
    val libraryBuilder = projectBuilder.addModule(EmptyModuleFixtureBuilder::class.java)
    val bridgeBuilder = projectBuilder.addModule(EmptyModuleFixtureBuilder::class.java)
    val indirectAppBuilder = projectBuilder.addModule(EmptyModuleFixtureBuilder::class.java)
    fixture.setUp()
    fixture.project.enableImmediateAutomaticRefresh()

    val appModule = appBuilder.fixture.module
    val libraryModule = libraryBuilder.fixture.module
    val bridgeModule = bridgeBuilder.fixture.module
    val indirectAppModule = indirectAppBuilder.fixture.module
    val appRoot = fixture.tempDirFixture.findOrCreateDir("app")
    val libraryRoot = fixture.tempDirFixture.findOrCreateDir("library")
    val bridgeRoot = fixture.tempDirFixture.findOrCreateDir("bridge")
    val indirectAppRoot = fixture.tempDirFixture.findOrCreateDir("indirectApp")
    ModuleRootModificationUtil.updateModel(appModule) { model ->
      model.addContentEntry(appRoot).addSourceFolder(appRoot, false)
    }
    ModuleRootModificationUtil.updateModel(libraryModule) { model ->
      model.addContentEntry(libraryRoot).addSourceFolder(libraryRoot, false)
    }
    ModuleRootModificationUtil.updateModel(bridgeModule) { model ->
      model.addContentEntry(bridgeRoot).addSourceFolder(bridgeRoot, false)
    }
    ModuleRootModificationUtil.updateModel(indirectAppModule) { model ->
      model.addContentEntry(indirectAppRoot).addSourceFolder(indirectAppRoot, false)
    }
    ModuleRootModificationUtil.addDependency(appModule, libraryModule)
    ModuleRootModificationUtil.addDependency(bridgeModule, libraryModule)
    ModuleRootModificationUtil.addDependency(indirectAppModule, bridgeModule)
    appModule.addMetroRuntimeLibrary()
    libraryModule.addMetroRuntimeLibrary()
    bridgeModule.addMetroRuntimeLibrary()
    indirectAppModule.addMetroRuntimeLibrary()
    fixture.project.setMetroOptions()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
  }

  override fun tearDown() {
    try {
      fixture.tearDown()
    } catch (e: Throwable) {
      addSuppressedException(e)
    } finally {
      super.tearDown()
    }
  }

  fun testClassArrivalInInvisibleModulePreservesAnUnresolvedGraphSnapshot() {
    val graph =
      fixture.addFileToProject(
        "library/lib/LibraryGraph.kt",
        """
        package lib
        import dev.zacsweers.metro.DependencyGraph
        @DependencyGraph interface LibraryGraph { val registry: NewRegistry }
        """
          .trimIndent(),
      ) as KtFile
    val service = fixture.project.service<MetroResolutionService>()
    val initial = service.awaitIndex(graph)
    val accessor = graph.declarationsIncludingNested().property("registry")
    assertTrue(initial.consumerEntryAt(accessor)!!.key.type.isError)

    // The app depends on the library, so its declarations are invisible from the library graph.
    fixture.addFileToProject("app/lib/NewRegistry.kt", "package lib; object NewRegistry")
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    assertSame(initial, service.awaitIndex(graph))

    fixture.addFileToProject("library/lib/NewRegistry.kt", "package lib; object NewRegistry")
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val updated = service.awaitIndex(graph)
    val consumer = updated.consumerEntryAt(accessor)!!
    val binding = updated.resolveConsumer(consumer).uniformBindings.orEmpty().single()
    assertTrue(binding is KaBinding.ConstructorInjected && binding.isObject)
    assertEquals("lib.NewRegistry", binding.typeKey.renderedType)
  }

  fun testResolutionInputsReuseModuleViewsAcrossOptionTargetsAndEdits() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibraryService.kt",
        """
        package lib

        import dev.zacsweers.metro.Inject

        @Inject class LibraryService
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.DependencyGraph
        import lib.LibraryService

        @DependencyGraph
        interface AppGraph {
          val service: LibraryService
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions("enable-dagger-anvil-interop" to "true")
    libraryModule.setModuleMetroOptions("enable-dagger-anvil-interop" to "false")
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val service = fixture.project.service<MetroResolutionService>()
    service.awaitIndex(appFile)
    val libraryIndex = service.awaitIndex(libraryFile)
    val index = service.awaitIndex(appFile)
    val inputs = index.resolutionInputs
    val appView = checkNotNull(inputs.moduleViewFor(appFile.virtualFile))
    val libraryView = checkNotNull(inputs.moduleViewFor(libraryFile.virtualFile))

    assertSame(inputs.fileOrdinalTable, appView.fileOrdinalTable)
    assertSame(inputs.fileOrdinalTable, libraryView.fileOrdinalTable)
    assertFalse(appView.sharesVisibilityArrayWith(libraryView))
    assertTrue(appView.resolutionScope.contains(libraryFile))
    assertFalse(libraryView.resolutionScope.contains(appFile))
    assertTrue(appView.daggerAnvilInteropEnabled)
    assertFalse(libraryView.daggerAnvilInteropEnabled)
    assertNotSame(index, libraryIndex)
    assertSame(index.generationToken, libraryIndex.generationToken)
    assertSame(appView, libraryIndex.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertSame(libraryView, libraryIndex.resolutionInputs.moduleViewFor(libraryFile.virtualFile))

    val documents = PsiDocumentManager.getInstance(fixture.project)
    val document = checkNotNull(documents.getDocument(appFile))
    WriteCommandAction.runWriteCommandAction(fixture.project) {
      document.insertString(document.text.lastIndexOf('}'), "  val otherService: LibraryService\n")
    }
    documents.commitAllDocuments()
    val updated = service.awaitIndex(appFile)
    assertNotSame(index.generationToken, updated.generationToken)
    assertNotSame(inputs, updated.resolutionInputs)
    assertSame(inputs.fileOrdinalTable, updated.resolutionInputs.fileOrdinalTable)
    assertSame(appView, updated.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertSame(libraryView, updated.resolutionInputs.moduleViewFor(libraryFile.virtualFile))
    val accessor = appFile.declarationsIncludingNested().property("otherService")
    val consumer = updated.consumerEntryAt(accessor)!!
    assertNull(inputs.sourceIdentity(consumer.pointer))
    assertEquals(
      checkNotNull(sourcePointerIdentity(consumer.pointer)),
      updated.resolutionInputs.sourceIdentity(consumer.pointer),
    )
  }

  fun testModuleVisibilityRefreshesWhenDependenciesChange() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibraryService.kt",
        """
        package lib
        import dev.zacsweers.metro.Inject
        @Inject class LibraryService
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app
        import dev.zacsweers.metro.DependencyGraph
        @DependencyGraph interface AppGraph
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val service = fixture.project.service<MetroResolutionService>()
    val initial = service.awaitIndex(appFile)
    val initialView = checkNotNull(initial.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertTrue(initialView.resolutionScope.contains(libraryFile))

    ModuleRootModificationUtil.updateModel(appModule) { model ->
      val dependency =
        model.orderEntries.filterIsInstance<ModuleOrderEntry>().single {
          it.module == libraryModule
        }
      model.removeOrderEntry(dependency)
    }
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val updated = service.awaitIndex(appFile)
    val updatedView = checkNotNull(updated.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertNotSame(initialView, updatedView)
    assertFalse(updatedView.resolutionScope.contains(libraryFile))
    assertTrue(initialView.resolutionScope.contains(libraryFile))
  }

  fun testModuleVisibilityTracksAddedAndRemovedFiles() {
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app
        import dev.zacsweers.metro.DependencyGraph
        @DependencyGraph interface AppGraph { val service: lib.LibraryService }
        """
          .trimIndent(),
      ) as KtFile
    val service = fixture.project.service<MetroResolutionService>()
    val initial = service.awaitIndex(appFile)
    val initialView = checkNotNull(initial.resolutionInputs.moduleViewFor(appFile.virtualFile))
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibraryService.kt",
        """
        package lib
        import dev.zacsweers.metro.Inject
        @Inject class LibraryService
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val added = service.awaitIndex(appFile)
    val addedView = checkNotNull(added.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertNotSame(initialView, addedView)
    assertTrue(addedView.resolutionScope.contains(libraryFile))

    val libraryVirtualFile = libraryFile.virtualFile
    val libraryDirectory = libraryVirtualFile.parent
    val outside = fixture.tempDirFixture.findOrCreateDir("outside")
    // Moving outside content keeps the file alive while removing it from module visibility.
    WriteCommandAction.runWriteCommandAction(fixture.project) {
      libraryVirtualFile.move(this, outside)
    }
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val moved = service.awaitIndex(appFile)
    assertNull(moved.resolutionInputs.moduleViewFor(libraryVirtualFile))
    assertNull(moved.resolutionInputs.fileOrdinal(libraryVirtualFile))

    WriteCommandAction.runWriteCommandAction(fixture.project) {
      libraryVirtualFile.move(this, libraryDirectory)
    }
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val restored = service.awaitIndex(appFile)
    assertNotNull(restored.resolutionInputs.moduleViewFor(libraryVirtualFile))

    WriteCommandAction.runWriteCommandAction(fixture.project) { libraryVirtualFile.delete(this) }
    val removed = service.awaitIndex(appFile)
    assertNull(removed.resolutionInputs.moduleViewFor(libraryVirtualFile))
    assertNull(removed.resolutionInputs.fileOrdinal(libraryVirtualFile))
  }

  fun testModuleVisibilityRefreshesInteropSettings() {
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app
        import dev.zacsweers.metro.DependencyGraph
        @DependencyGraph interface AppGraph
        """
          .trimIndent(),
      ) as KtFile
    val service = fixture.project.service<MetroResolutionService>()
    val initial = service.awaitIndex(appFile)
    val initialView = checkNotNull(initial.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertFalse(initialView.daggerAnvilInteropEnabled)

    fixture.project.setMetroOptions("enable-dagger-anvil-interop" to "true")
    val updated = service.awaitIndex(appFile)
    val updatedView = checkNotNull(updated.resolutionInputs.moduleViewFor(appFile.virtualFile))
    assertNotSame(initialView, updatedView)
    assertTrue(updatedView.daggerAnvilInteropEnabled)
    assertFalse(initialView.daggerAnvilInteropEnabled)
  }

  fun testFacetOptionsInvalidateCachedGraphDataBeforeDeferredCallbacks() {
    val file =
      fixture.addFileToProject(
        "library/lib/LibraryGraph.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        @Inject class Value

        @DependencyGraph interface LibraryGraph {
          val provider: () -> Value
        }
        """
          .trimIndent(),
      ) as KtFile
    val service = fixture.project.service<MetroResolutionService>()
    val validation = fixture.project.service<MetroGraphValidationService>()
    val initial = service.awaitIndex(file)
    val initialGraph = initial.graphs.single { it.name == "LibraryGraph" }
    assertTrue(
      initial.accessorsFor(initialGraph).single().contextKey.wrappedType is WrappedType.Provider
    )
    val initialResult =
      validation.validate(file, initial.contextsFor(initialGraph).single()).requireCompleted()
    assertTrue(
      initialResult.diagnostics.joinToString { it.render() },
      initialResult.diagnostics.isEmpty(),
    )
    val module = checkNotNull(ModuleUtilCore.findModuleForPsiElement(file))

    runInEdtAndWait {
      runWriteAction {
        module.setModuleMetroOptions("enable-function-providers" to "false")
        // Keep deferred callbacks and background reads behind the facet-change assertion.
        assertSame(BindingIndex.EMPTY, service.cachedIndex(file))
      }
    }

    val updated = service.awaitIndex(file)
    val updatedGraph = updated.graphs.single { it.name == "LibraryGraph" }
    assertNotSame(initial, updated)
    assertTrue(
      updated.accessorsFor(updatedGraph).single().contextKey.wrappedType is WrappedType.Canonical
    )
    val updatedResult =
      validation.validate(file, updated.contextsFor(updatedGraph).single()).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), updatedResult.diagnostics.map { it.id })
  }

  fun testTrackedDirectoryMoveRefreshesModuleOptionsAndDependentShards() {
    val libraryFile =
      fixture.addFileToProject(
        "library/moved/MovedGraph.kt",
        """
        package moved

        import dev.zacsweers.metro.*

        @Inject class MovedValue

        interface MovedAccessors {
          val value: MovedValue
        }

        @DependencyGraph
        interface MovedGraph {
          val provider: () -> MovedValue
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.DependencyGraph
        import moved.MovedAccessors

        @DependencyGraph interface AppGraph : MovedAccessors
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions(
      "enable-function-providers" to "true",
      "enable-dagger-anvil-interop" to "true",
    )
    libraryModule.setModuleMetroOptions(
      "enable-function-providers" to "false",
      "enable-dagger-anvil-interop" to "false",
    )
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val service = fixture.project.service<MetroResolutionService>()
    service.awaitIndex(appFile)
    val initial = service.awaitIndex(libraryFile)
    val initialGraph = initial.graphs.single { it.name == "MovedGraph" }
    val initialAppGraph = initial.graphs.single { it.name == "AppGraph" }
    val initialInherited = initial.consumers.single { it.graphId == initialAppGraph.declarationId }
    val initialModule =
      checkNotNull(initial.resolutionInputs.moduleViewFor(libraryFile.virtualFile))
    assertFalse(initialGraph.daggerAnvilInteropEnabled)
    assertFalse(initialModule.daggerAnvilInteropEnabled)
    assertFalse(initialModule.resolutionScope.contains(appFile))
    assertFalse(
      fixture.project
        .service<MetroIdeProjectService>()
        .state(libraryModule)
        .options
        .enableFunctionProviders
    )
    val initialAccessor = initial.accessorsFor(initialGraph).single()
    assertTrue(
      "The library graph must retain the function request when function providers are disabled",
      initialAccessor.contextKey.wrappedType is WrappedType.Canonical,
    )
    val validation = fixture.project.service<MetroGraphValidationService>()
    val initialResult =
      validation
        .validate(libraryFile, initial.contextsFor(initialGraph).single())
        .requireCompleted()
    assertEquals(
      listOf(MetroDiagnosticId.MISSING_BINDING),
      initialResult.diagnostics.map { it.id },
    )

    val movedFile = moveTrackedDirectory(libraryFile, "app")
    assertEquals(appModule, ModuleUtilCore.findModuleForPsiElement(movedFile))
    val updated = service.awaitIndex(movedFile)
    val updatedGraph = updated.graphs.single { it.name == "MovedGraph" }
    val updatedAppGraph = updated.graphs.single { it.name == "AppGraph" }
    val updatedInherited = updated.consumers.single { it.graphId == updatedAppGraph.declarationId }
    val updatedModule = checkNotNull(updated.resolutionInputs.moduleViewFor(movedFile.virtualFile))
    assertNotSame(initial, updated)
    assertEquals(initialGraph.declarationId, updatedGraph.declarationId)
    assertTrue(updatedGraph.daggerAnvilInteropEnabled)
    assertTrue(updatedModule.daggerAnvilInteropEnabled)
    assertFalse(initialModule.module == updatedModule.module)
    assertTrue(updatedModule.resolutionScope.contains(appFile))
    // The unchanged app file cached inherited members from the moved declaration's file.
    assertNotSame(initialInherited, updatedInherited)
    assertEquals(initialInherited.key, updatedInherited.key)
    for ((file, graph) in listOf(movedFile to updatedGraph, appFile to updatedAppGraph)) {
      val result = validation.validate(file, updated.contextsFor(graph).single()).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testTrackedDirectoryMoveRefreshesLibraryLookupWithUnchangedOptionsAndKeys() {
    val sourceClass =
      fixture.addFileToProject(
        "app/libtest/LibRetargetedDependencyA.kt",
        """
        package libtest

        import dev.zacsweers.metro.Inject

        @Inject class LibRetargetedDependencyA
        """
          .trimIndent(),
      ) as KtFile
    val graphFile =
      fixture.addFileToProject(
        "app/moved/MovedGraph.kt",
        """
        package moved

        import dev.zacsweers.metro.DependencyGraph
        import libtest.LibRetargetedDependencyA

        @DependencyGraph
        interface MovedGraph {
          val value: LibRetargetedDependencyA
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/BridgeMarker.kt",
        "package bridge\n\n@dev.zacsweers.metro.Inject class BridgeMarker",
      ) as KtFile
    val bridgeModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(bridgeFile))

    // Each module resolves the same class name from a different declaration.
    bridgeModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
      val service = fixture.project.service<MetroResolutionService>()
      service.awaitIndex(bridgeFile)
      val initial = service.awaitIndex(graphFile)
      val initialGraph = initial.graphs.single { it.name == "MovedGraph" }
      val initialConsumer = initial.consumers.single { it.graphId == initialGraph.declarationId }
      assertEquals(
        listOf(sourceClass.virtualFile),
        initial.resolveConsumer(initialConsumer).uniformBindings.orEmpty().map {
          it.pointer.virtualFile
        },
      )
      val validation = fixture.project.service<MetroGraphValidationService>()
      val initialResult =
        validation
          .validate(graphFile, initial.contextsFor(initialGraph).single())
          .requireCompleted()
      assertTrue(
        initialResult.diagnostics.joinToString { it.render() },
        initialResult.diagnostics.isEmpty(),
      )

      val movedFile = moveTrackedDirectory(graphFile, "bridge")
      assertEquals(bridgeModule, ModuleUtilCore.findModuleForPsiElement(movedFile))
      val updated = service.awaitIndex(movedFile)
      val updatedGraph = updated.graphs.single { it.name == "MovedGraph" }
      val updatedConsumer = updated.consumers.single { it.graphId == updatedGraph.declarationId }
      assertNotSame(initial, updated)
      assertEquals(initialGraph.declarationId, updatedGraph.declarationId)
      assertEquals(initialConsumer.key, updatedConsumer.key)
      val moduleView = checkNotNull(updated.resolutionInputs.moduleViewFor(movedFile.virtualFile))
      assertEquals(
        KaModuleProvider.getModule(fixture.project, movedFile, useSiteModule = null),
        moduleView.module,
      )
      assertFalse(moduleView.resolutionScope.contains(sourceClass))
      val resolved = updated.resolveConsumer(updatedConsumer).uniformBindings.orEmpty()
      assertEquals(1, resolved.size)
      assertFalse(sourceClass.virtualFile == resolved.single().pointer.virtualFile)
      val result =
        validation
          .validate(movedFile, updated.contextsFor(updatedGraph).single())
          .requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    }
  }

  fun testTrackedDirectoryMoveOfSharedDeclarationsRefreshesUnmovedGraphs() {
    val definitions =
      fixture.addFileToProject(
        "library/shared/Definitions.kt",
        """
        package shared

        @dev.zacsweers.metro.Inject class Marker
        typealias Alias = String
        const val SERVICE_NAME = "before"
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import shared.Alias
        import shared.SERVICE_NAME

        @DependencyGraph
        interface AppGraph {
          @Named("before") val service: String

          @Provides @Named(SERVICE_NAME) fun provideAlias(): Alias = error("unused")
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    val service = fixture.project.service<MetroResolutionService>()
    val initial = service.awaitIndex(appFile)
    val initialGraph = initial.graphs.single { it.name == "AppGraph" }
    val initialConsumer = initial.consumers.single { it.graphId == initialGraph.declarationId }
    assertEquals(
      "Consumer ${initialConsumer.key}; bindings ${initial.bindings.map { it.typeKey }}",
      1,
      initial.resolveConsumer(initialConsumer).uniformBindings.orEmpty().size,
    )
    val initialBinding =
      initial.bindings.single {
        (it.pointer.element as? KtNamedDeclaration)?.name == "provideAlias"
      }

    // The fingerprint text stays equal while the app loses access to both shared declarations.
    moveTrackedDirectory(definitions, "bridge")
    val updated = service.awaitIndex(appFile)
    assertNotSame(initial, updated)
    assertTrue(updated.bindings.none { it.typeKey == initialBinding.typeKey })
    val graph = updated.graphs.single { it.name == "AppGraph" }
    val result =
      fixture.project
        .service<MetroGraphValidationService>()
        .validate(appFile, updated.contextsFor(graph).single())
        .requireCompleted()
    assertTrue(result.diagnostics.any { it.id == MetroDiagnosticId.MISSING_BINDING })
  }

  /** Moves a tracked subtree without rewriting Kotlin text or replacing its virtual files. */
  private fun moveTrackedDirectory(file: KtFile, destinationRoot: String): KtFile {
    val virtualFile = file.virtualFile
    val modificationStamp = virtualFile.modificationStamp
    val directory = virtualFile.parent
    val destination = fixture.tempDirFixture.findOrCreateDir(destinationRoot)
    val rootTracker = ProjectRootModificationTracker.getInstance(fixture.project)
    val rootsBeforeMove = rootTracker.modificationCount
    WriteCommandAction.runWriteCommandAction(fixture.project) { directory.move(this, destination) }
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
    assertEquals(virtualFile, destination.findChild(directory.name)?.findChild(virtualFile.name))
    assertEquals(modificationStamp, virtualFile.modificationStamp)
    assertEquals(rootsBeforeMove, rootTracker.modificationCount)
    return checkNotNull(PsiManager.getInstance(fixture.project).findFile(virtualFile) as? KtFile)
  }

  fun testContributedInterfacesProvideInheritedRootsAcrossModules() {
    val apiFile =
      fixture.addFileToProject(
        "library/repro/api/GraphApi.kt",
        """
        package repro.api

        import dev.zacsweers.metro.*

        interface BaseUserGraph
        interface BaseLoginGraph
        interface BaseAccountUserGraph

        interface BaseAppGraph {
          val baseUserGraph: BaseUserGraph
          val baseLoginGraph: BaseLoginGraph?
          val baseAccountUserGraph: BaseAccountUserGraph?
        }

        abstract class ViewModelScope private constructor()
        abstract class LoginScope private constructor()
        abstract class LoginViewModelScope private constructor()

        interface ViewModelGraph {
          val value: String

          interface Factory {
            fun create(value: String): ViewModelGraph
          }
        }

        interface ViewModelGraphFactoryProvider {
          val viewModelGraphFactory: ViewModelGraph.Factory
        }

        @DependencyGraph(AppScope::class)
        interface ApiOnlyGraph : BaseAppGraph, ViewModelGraphFactoryProvider
        """
          .trimIndent(),
      ) as KtFile
    val implementationFile =
      fixture.addFileToProject(
        "bridge/repro/impl/AppModule.kt",
        """
        package repro.impl

        import dev.zacsweers.metro.*
        import repro.api.*

        @ContributesTo(AppScope::class)
        interface AppModule {
          @Provides
          fun provideBaseUserGraph(): BaseUserGraph = object : BaseUserGraph {}

          @Provides
          fun provideBaseLoginGraph(user: BaseUserGraph): BaseLoginGraph? = null

          @Provides
          fun provideBaseAccountUserGraph(user: BaseUserGraph): BaseAccountUserGraph? = null
        }

        @GraphExtension(ViewModelScope::class)
        interface AppViewModelGraph : ViewModelGraph {
          @ContributesTo(AppScope::class)
          @GraphExtension.Factory
          interface Factory : ViewModelGraph.Factory {
            override fun create(@Provides value: String): AppViewModelGraph
          }
        }

        @GraphExtension(LoginViewModelScope::class)
        interface LoginViewModelGraph : ViewModelGraph {
          @ContributesTo(LoginScope::class)
          @GraphExtension.Factory
          interface Factory : ViewModelGraph.Factory {
            override fun create(@Provides value: String): LoginViewModelGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/repro/app/AppGraph.kt",
        """
        package repro.app

        import dev.zacsweers.metro.*
        import repro.api.BaseAppGraph
        import repro.api.ViewModelGraphFactoryProvider

        @DependencyGraph(AppScope::class)
        interface AppGraph : BaseAppGraph, ViewModelGraphFactoryProvider

        @DependencyGraph(
          AppScope::class,
          excludes = [repro.impl.AppModule::class, repro.impl.AppViewModelGraph.Factory::class],
        )
        interface ExcludedAppGraph : BaseAppGraph, ViewModelGraphFactoryProvider
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val implementationModule =
      checkNotNull(ModuleUtilCore.findModuleForPsiElement(implementationFile))
    ModuleRootModificationUtil.addDependency(appModule, implementationModule)
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    // Synchronous validation needs a current index for each graph's declaration module.
    fixture.project.service<MetroResolutionService>().awaitIndex(apiFile)
    fixture.project.service<MetroResolutionService>().awaitIndex(implementationFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val validation = fixture.project.service<MetroGraphValidationService>()
    val graph =
      checkNotNull(index.graphEntryAt(appFile.declarationsIncludingNested().klass("AppGraph")))
    val context = index.contextsFor(graph).single()
    val queryContext = checkNotNull(index.queryContext(context))
    assertEquals(
      KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null),
      queryContext.graphModule,
    )
    assertEquals(appFile.virtualFile, graph.declarationId.file)

    val expectedRoots =
      setOf(
        "repro.api.BaseUserGraph",
        "repro.api.BaseLoginGraph?",
        "repro.api.BaseAccountUserGraph?",
        "repro.api.ViewModelGraph.Factory",
      )
    assertEquals(
      expectedRoots,
      index.accessorsFor(graph).mapTo(mutableSetOf()) { it.key.renderedType },
    )
    val result = validation.validate(appFile, context).requireCompleted()
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val resolved = result.bindings.asMap().entries.associate { it.key.renderedType to it.value }
    assertTrue(resolved.keys.toString(), resolved.keys.containsAll(expectedRoots))
    val expectedProviders =
      mapOf(
        "repro.api.BaseUserGraph" to "provideBaseUserGraph",
        "repro.api.BaseLoginGraph?" to "provideBaseLoginGraph",
        "repro.api.BaseAccountUserGraph?" to "provideBaseAccountUserGraph",
      )
    for ((key, name) in expectedProviders) {
      val binding = resolved.getValue(key)
      assertTrue(binding.toString(), binding is KaBinding.Provided)
      assertEquals(implementationFile.virtualFile, binding.pointer.virtualFile)
      assertEquals(name, (binding.pointer.element as? KtNamedDeclaration)?.name)
    }
    assertEquals(listOf("AppViewModelGraph"), index.extensionsOf(queryContext).map { it.name })
    val childContext = index.extensionContextsOf(context).single()
    assertEquals("AppViewModelGraph", childContext.graph.name)
    assertEquals(listOf(childContext.graph, graph), childContext.chain)
    val childResult = validation.validate(implementationFile, childContext).requireCompleted()
    assertTrue(
      childResult.diagnostics.joinToString { it.render() },
      childResult.diagnostics.isEmpty(),
    )
    assertTrue(
      childResult.bindings.any { key, binding ->
        key.renderedType == "kotlin.String" && binding is KaBinding.BoundInstance
      }
    )

    // The API module cannot see the implementation module. Project-wide source discovery must
    // not make either its providers or its contributed factory visible to this separate graph.
    val apiGraph =
      checkNotNull(index.graphEntryAt(apiFile.declarationsIncludingNested().klass("ApiOnlyGraph")))
    val apiContext = index.contextsFor(apiGraph).single()
    val apiResult = validation.validate(apiFile, apiContext).requireCompleted()
    assertEquals(4, apiResult.diagnostics.size)
    assertTrue(
      apiResult.diagnostics.joinToString { it.render() },
      apiResult.diagnostics.all { it.id == MetroDiagnosticId.MISSING_BINDING },
    )
    assertTrue(index.extensionsOf(checkNotNull(index.queryContext(apiContext))).isEmpty())

    val excludedGraph =
      checkNotNull(
        index.graphEntryAt(appFile.declarationsIncludingNested().klass("ExcludedAppGraph"))
      )
    val excludedContext = index.contextsFor(excludedGraph).single()
    val excludedResult = validation.validate(appFile, excludedContext).requireCompleted()
    assertEquals(4, excludedResult.diagnostics.size)
    assertTrue(
      excludedResult.diagnostics.joinToString { it.render() },
      excludedResult.diagnostics.all { it.id == MetroDiagnosticId.MISSING_BINDING },
    )
    assertTrue(index.extensionsOf(checkNotNull(index.queryContext(excludedContext))).isEmpty())
  }

  fun testSelectedContributedDefaultOverridesSuppressOnlyTheirAbstractDeclarations() {
    val apiFile =
      fixture.addFileToProject(
        "library/defaults/api/Accessors.kt",
        """
        package defaults.api

        import dev.zacsweers.metro.*

        interface Value
        class ConcreteValue : Value
        @Inject class Needed

        @ContributesTo(AppScope::class)
        interface PublicAccessors {
          val value: Value
        }
        """
          .trimIndent(),
      ) as KtFile
    val implementationFile =
      fixture.addFileToProject(
        "bridge/defaults/impl/DefaultBindings.kt",
        """
        package defaults.impl

        import dev.zacsweers.metro.*
        import defaults.api.*

        @ContributesTo(AppScope::class)
        interface DefaultBindings : PublicAccessors {
          val needed: Needed
          override val value: ConcreteValue get() = ConcreteValue()
        }

        @ContributesTo(AppScope::class, replaces = [DefaultBindings::class])
        interface RemoveDefaults
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/defaults/app/Graphs.kt",
        """
        package defaults.app

        import dev.zacsweers.metro.*
        import defaults.api.*
        import defaults.impl.*

        @DependencyGraph(AppScope::class, excludes = [RemoveDefaults::class])
        interface AppGraph

        @DependencyGraph(
          AppScope::class,
          excludes = [DefaultBindings::class, RemoveDefaults::class],
        )
        interface ExcludedGraph

        @DependencyGraph(AppScope::class)
        interface ReplacedGraph

        @DependencyGraph(AppScope::class, excludes = [RemoveDefaults::class])
        interface UnrelatedGraph {
          val unrelated: Value
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    ModuleRootModificationUtil.addDependency(
      appModule,
      checkNotNull(ModuleUtilCore.findModuleForPsiElement(implementationFile)),
    )
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val validation = fixture.project.service<MetroGraphValidationService>()
    fun context(name: String) = index.contextsFor(index.graphs.single { it.name == name }).single()
    fun accessors(name: String) =
      index.accessorsFor(checkNotNull(index.queryContext(context(name))))

    assertEquals(
      listOf("needed"),
      accessors("AppGraph").map { (it.pointer.element as? KtNamedDeclaration)?.name },
    )
    val completed = validation.validate(appFile, context("AppGraph")).requireCompleted()
    assertTrue(completed.diagnostics.joinToString { it.render() }, completed.diagnostics.isEmpty())
    assertTrue(completed.bindings.any { key, _ -> key.renderedType == "defaults.api.Needed" })
    assertFalse(completed.bindings.any { key, _ -> key.renderedType == "defaults.api.Value" })

    val abstractValue = apiFile.declarationsIncludingNested().property("value")
    for (graphName in listOf("ExcludedGraph", "ReplacedGraph")) {
      val accessor = accessors(graphName).single()
      assertSame(abstractValue, accessor.pointer.element)
      val result = validation.validate(appFile, context(graphName)).requireCompleted()
      assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
    }

    val unrelatedAccessors = accessors("UnrelatedGraph")
    assertEquals(
      setOf("needed", "unrelated"),
      unrelatedAccessors.mapTo(mutableSetOf()) {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    val unrelated = validation.validate(appFile, context("UnrelatedGraph")).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), unrelated.diagnostics.map { it.id })
    assertTrue(unrelated.diagnostics.single().render().contains("unrelated"))
  }

  fun testWrittenDefaultOverridesAndOptionalRootsRemainDistinct() {
    fixture.addFileToProject(
      "library/defaultroots/api/Accessors.kt",
      """
      package defaultroots.api

      import dev.zacsweers.metro.*

      interface Value
      class ConcreteValue : Value
      @Inject class Needed
      abstract class OptionalScope private constructor()

      interface WrittenBase<T> {
        val writtenValue: T
      }

      @ContributesTo(OptionalScope::class)
      interface OptionalAccessors {
        val optionalValue: Value?
      }
      """
        .trimIndent(),
    )
    val implementationFile =
      fixture.addFileToProject(
        "bridge/defaultroots/impl/OptionalDefaults.kt",
        """
        package defaultroots.impl

        import dev.zacsweers.metro.*
        import defaultroots.api.*

        @ContributesTo(OptionalScope::class)
        interface OptionalDefaults : OptionalAccessors {
          @OptionalBinding
          override val optionalValue: Value? get() = null
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/defaultroots/app/Graphs.kt",
        """
        package defaultroots.app

        import dev.zacsweers.metro.*
        import defaultroots.api.*

        interface WrittenDefaults : WrittenBase<Value> {
          val needed: Needed
          override val writtenValue: ConcreteValue get() = ConcreteValue()
        }

        @DependencyGraph
        interface WrittenGraph : WrittenDefaults

        @DependencyGraph(OptionalScope::class)
        interface OptionalGraph

        @DependencyGraph(OptionalScope::class)
        interface RequiredGraph {
          val requiredValue: Value?
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    ModuleRootModificationUtil.addDependency(
      appModule,
      checkNotNull(ModuleUtilCore.findModuleForPsiElement(implementationFile)),
    )
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val validation = fixture.project.service<MetroGraphValidationService>()
    fun context(name: String) = index.contextsFor(index.graphs.single { it.name == name }).single()
    fun accessors(name: String) =
      index.accessorsFor(checkNotNull(index.queryContext(context(name))))

    assertEquals(
      listOf("needed"),
      accessors("WrittenGraph").map { (it.pointer.element as? KtNamedDeclaration)?.name },
    )
    val written = validation.validate(appFile, context("WrittenGraph")).requireCompleted()
    assertTrue(written.diagnostics.joinToString { it.render() }, written.diagnostics.isEmpty())

    val optionalAccessor = accessors("OptionalGraph").single()
    assertTrue(optionalAccessor.isOptional)
    assertSame(
      implementationFile.declarationsIncludingNested().property("optionalValue"),
      optionalAccessor.pointer.element,
    )
    val optional = validation.validate(appFile, context("OptionalGraph")).requireCompleted()
    assertTrue(optional.diagnostics.joinToString { it.render() }, optional.diagnostics.isEmpty())

    val requiredAccessors = accessors("RequiredGraph")
    assertEquals(2, requiredAccessors.size)
    assertEquals(1, requiredAccessors.count { it.isOptional })
    val required = validation.validate(appFile, context("RequiredGraph")).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), required.diagnostics.map { it.id })
    assertTrue(required.diagnostics.single().render().contains("requiredValue"))
  }

  fun testContributedChildInterfaceUsesItsParentPathAndModule() {
    val apiFile =
      fixture.addFileToProject(
        "library/pathrepro/GraphApi.kt",
        """
        package pathrepro

        import dev.zacsweers.metro.*

        interface ChildScope
        interface Value

        @GraphExtension(ChildScope::class)
        interface SharedChild {
          val value: Value
        }
        """
          .trimIndent(),
      ) as KtFile
    val implementationFile =
      fixture.addFileToProject(
        "bridge/pathrepro/ChildBindings.kt",
        """
        package pathrepro

        import dev.zacsweers.metro.*

        @ContributesTo(ChildScope::class)
        interface ChildBindings {
          @Provides fun provideValue(): Value = object : Value {}
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/pathrepro/Parents.kt",
        """
        package pathrepro

        import dev.zacsweers.metro.*

        @DependencyGraph(AppScope::class)
        interface AllowedParent {
          val child: SharedChild
        }

        @DependencyGraph(AppScope::class, excludes = [ChildBindings::class])
        interface ExcludedParent {
          val child: SharedChild
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    ModuleRootModificationUtil.addDependency(
      appModule,
      checkNotNull(ModuleUtilCore.findModuleForPsiElement(implementationFile)),
    )
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(apiFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val child =
      checkNotNull(index.graphEntryAt(apiFile.declarationsIncludingNested().klass("SharedChild")))
    val childContexts = index.contextsFor(child).associateBy { it.rootGraph.name }
    assertEquals(setOf("AllowedParent", "ExcludedParent"), childContexts.keys)
    val validation = fixture.project.service<MetroGraphValidationService>()
    val allowedContext = childContexts.getValue("AllowedParent")
    assertEquals(
      KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null),
      checkNotNull(index.queryContext(allowedContext)).graphModule,
    )
    val allowed = validation.validate(apiFile, allowedContext).requireCompleted()
    assertTrue(allowed.diagnostics.joinToString { it.render() }, allowed.diagnostics.isEmpty())
    val valueBinding =
      allowed.bindings.asMap().values.single { it.typeKey.renderedType == "pathrepro.Value" }
    assertEquals(implementationFile.virtualFile, valueBinding.pointer.virtualFile)
    assertEquals("provideValue", (valueBinding.pointer.element as? KtNamedDeclaration)?.name)

    val excluded =
      validation.validate(apiFile, childContexts.getValue("ExcludedParent")).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), excluded.diagnostics.map { it.id })
  }

  fun testContributedChildOwnershipSurvivesLibraryRefreshAndSourceReparse() {
    val apiFile =
      fixture.addFileToProject(
        "library/ownership/GraphApi.kt",
        """
        package ownership

        import dev.zacsweers.metro.*

        interface ChildScope

        @GraphExtension(ChildScope::class)
        interface SharedChild
        """
          .trimIndent(),
      ) as KtFile
    val accessorsFile =
      fixture.addFileToProject(
        "app/ownership/ChildAccessors.kt",
        """
        package ownership

        import dev.zacsweers.metro.*
        import libtest.LibRetargetedDependencyA

        interface LibraryAccessors {
          val required: LibRetargetedDependencyA
          val implemented: LibRetargetedDependencyA
        }

        @ContributesTo(ChildScope::class)
        interface AppChildAccessors : LibraryAccessors {
          override val implemented: LibRetargetedDependencyA get() = error("supplied")

          @Provides fun provideCount(dependency: LibRetargetedDependencyA): Int = 0
          @Provides fun provideText(dependency: LibRetargetedDependencyA): String = ""
        }

        @ContributesTo(ChildScope::class)
        interface ExcludedChildAccessors : LibraryAccessors
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/ownership/Parent.kt",
        """
        package ownership

        import dev.zacsweers.metro.*

        @DependencyGraph(excludes = [ExcludedChildAccessors::class])
        interface ParentGraph {
          val child: SharedChild
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val service = fixture.project.service<MetroResolutionService>()
    val settings = MetroSettings.getInstance(fixture.project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries

    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)
      service.awaitIndex(apiFile)
      val initial = service.awaitIndex(appFile)
      val ownership = ConsumerOwnershipBundle.build(initial)

      fun assertOwnership(index: BindingIndex) {
        val child = index.graphs.single { it.name == "SharedChild" }
        val parent = index.graphs.single { it.name == "ParentGraph" }
        fun consumer(name: String, contribution: String) =
          index.consumers.single {
            it.graphId == child.declarationId &&
              it.graphContribution?.classId?.shortClassName?.asString() == contribution &&
              (it.pointer.element as? KtNamedDeclaration)?.name == name
          }

        val required = consumer("required", "AppChildAccessors")
        assertEquals(
          listOf(parent.pointer.element),
          ownership.owningGraphPointers(required)?.map { it.element },
        )
        // The same key can belong to an implemented declaration or an excluded contribution.
        val implemented = consumer("implemented", "AppChildAccessors")
        assertTrue(ownership.owningGraphPointers(implemented)?.isEmpty() == true)
        val excluded = consumer("required", "ExcludedChildAccessors")
        assertTrue(ownership.owningGraphPointers(excluded)?.isEmpty() == true)
        val providerParameters =
          index.consumers.filter {
            it.graphId == child.declarationId &&
              (it.pointer.element as? KtNamedDeclaration)?.name == "dependency"
          }
        assertEquals(2, providerParameters.size)
        for (parameter in providerParameters) {
          assertEquals(
            listOf(parent.pointer.element),
            ownership.owningGraphPointers(parameter)?.map { it.element },
          )
        }
      }

      fun assertResolved(index: BindingIndex) {
        val child = index.graphs.single { it.name == "SharedChild" }
        val result =
          fixture.project
            .service<MetroGraphValidationService>()
            .validate(apiFile, index.contextsFor(child).single())
            .requireCompleted()
        assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
        assertTrue(
          result.bindings.any { key, _ -> key.renderedType == "libtest.LibRetargetedDependencyA" }
        )
      }

      fun rebuildLibraryIndex(): BindingIndex {
        settings.resolveFromLibraries = false
        service.settingsChanged()
        service.awaitIndex(appFile)
        settings.resolveFromLibraries = true
        service.settingsChanged()
        service.awaitIndex(apiFile)
        return service.awaitIndex(appFile)
      }

      try {
        assertOwnership(initial)
        assertResolved(initial)
        // Clearing the binary cache retains the source summary and recreates contributed consumers.
        val refreshed = rebuildLibraryIndex()
        assertOwnership(refreshed)
        assertResolved(refreshed)

        val document =
          checkNotNull(PsiDocumentManager.getInstance(fixture.project).getDocument(accessorsFile))
        WriteCommandAction.runWriteCommandAction(fixture.project) {
          document.insertString(
            0,
            "// Move declaration offsets without changing library requests.\n",
          )
        }
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
        val reparsed = rebuildLibraryIndex()
        assertOwnership(reparsed)
        assertResolved(reparsed)
      } finally {
        settings.resolveFromLibraries = previousResolveFromLibraries
        service.settingsChanged()
      }
    }
  }

  fun testConsumerResolutionUsesEachGraphModuleAsTheUseSite() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibraryBindings.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        interface Service
        interface ConsumerApi

        @Inject
        @ContributesBinding(AppScope::class)
        class LibConsumer(val service: Service) : ConsumerApi

        @DependencyGraph(AppScope::class)
        interface LibGraph

        @GraphExtension
        interface LibExtension {
          val extensionService: Service
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.ConsumerApi
        import lib.LibExtension
        import lib.Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val consumer: ConsumerApi
          val extension: LibExtension

          @Provides fun provideService(): Service = object : Service {}
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val serviceConsumer =
      index.consumerEntryAt(libraryFile.declarationsIncludingNested().parameter("service"))!!
    val resolution = index.resolveConsumer(serviceConsumer)

    assertNull(resolution.uniformBindings)
    assertEquals(
      listOf("provideService"),
      resolution.candidateBindings.map { (it.pointer.element as? KtNamedDeclaration)?.name },
    )
    assertEquals(
      setOf("AppGraph", "LibExtension", "LibGraph"),
      resolution.perContext.keys.mapTo(mutableSetOf()) { it.graph.name },
    )
    assertEquals(listOf("LibGraph"), resolution.emptyContexts.map { it.graph.name })

    val graphsByName = index.graphs.associateBy { it.name }
    val appContext =
      index.queryContext(index.contextsFor(graphsByName.getValue("AppGraph")).single())!!
    val libraryContext =
      index.queryContext(index.contextsFor(graphsByName.getValue("LibGraph")).single())!!
    assertEquals(
      listOf("provideService"),
      index.bindingsFor(serviceConsumer, appContext).map {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertTrue(index.bindingsFor(serviceConsumer, libraryContext).isEmpty())
    assertTrue(
      index.bindingsInContext(appContext).any {
        (it.pointer.element as? KtNamedDeclaration)?.name == "provideService"
      }
    )
    assertTrue(
      index.bindingsInContext(libraryContext).none {
        (it.pointer.element as? KtNamedDeclaration)?.name == "provideService"
      }
    )

    val extensionConsumer =
      index.consumerEntryAt(
        libraryFile.declarationsIncludingNested().property("extensionService")
      )!!
    val extensionResolution = index.resolveConsumer(extensionConsumer)
    assertEquals(
      listOf("provideService"),
      extensionResolution.uniformBindings.orEmpty().map {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    val extensionContext = extensionResolution.perContext.keys.single()
    assertEquals(listOf("LibExtension", "AppGraph"), extensionContext.chain.map { it.name })
    assertEquals(appContext.graphModule, index.queryContext(extensionContext)!!.graphModule)
    val rootModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val extensionLabel = extensionContext.compilationContextName()
    assertTrue(extensionLabel, "${rootModule.name}: ${appFile.name}" in extensionLabel)
  }

  fun testDynamicGraphUsesTheCallSiteModuleAndReplacesLibraryGraphBindings() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibGraph.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        @BindingContainer
        object RealBindings {
          @Provides fun provideReal(): String = "real"
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface LibGraph {
          val value: String
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/DynamicGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.LibGraph

        @BindingContainer
        object FakeBindings {
          @Provides fun provideFake(): String = "fake"
        }

        val graph = createDynamicGraph<LibGraph>(FakeBindings)
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val graph = index.graphs.single { it.name == "LibGraph" }
    val contexts = index.contextsFor(graph)
    val staticContext = contexts.single { it.dynamicGraph == null }
    val dynamicContext = contexts.single { it.dynamicGraph != null }
    val appKaModule = KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null)
    val consumer =
      checkNotNull(
        index.consumerEntryAt(libraryFile.declarationsIncludingNested().property("value"))
      )

    assertEquals(appKaModule, index.queryContext(dynamicContext)!!.graphModule)
    val callerModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val dynamicLabel = dynamicContext.compilationContextName()
    assertTrue(dynamicLabel, "${callerModule.name}: DynamicGraph.kt" in dynamicLabel)
    assertEquals(
      listOf("provideReal"),
      index.bindingsFor(consumer, index.queryContext(staticContext)!!).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertEquals(
      listOf("provideFake"),
      index.bindingsFor(consumer, index.queryContext(dynamicContext)!!).mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertTrue(
      fixture.project
        .service<MetroGraphValidationService>()
        .validate(appFile, dynamicContext)
        .requireCompleted()
        .diagnostics
        .isEmpty()
    )
  }

  fun testDynamicGraphUsesCallSiteAnvilInteropForContributionRanks() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibGraph.kt",
        """
        package lib

        import dev.zacsweers.metro.*
        import kotlin.reflect.KClass

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @Inject @RankedBinding(AppScope::class, rank = 50)
        class LowerService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface LibGraph {
          val service: Service
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/DynamicGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.LibGraph

        @BindingContainer
        object DynamicBindings {
          @Provides fun unrelated(): String = "dynamic"
        }

        val graph = createDynamicGraph<LibGraph>(DynamicBindings)
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions(
      "custom-contributes-binding" to "lib/RankedBinding",
      "enable-dagger-anvil-interop" to "true",
    )
    libraryModule.setModuleMetroOptions(
      "custom-contributes-binding" to "lib/RankedBinding",
      "enable-dagger-anvil-interop" to "false",
    )
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val graph = index.graphs.single { it.name == "LibGraph" }
    val contexts = index.contextsFor(graph)
    val staticContext = contexts.single { it.dynamicGraph == null }
    val dynamicContext = contexts.single { it.dynamicGraph != null }
    val consumer =
      checkNotNull(
        index.consumerEntryAt(libraryFile.declarationsIncludingNested().property("service"))
      )

    assertFalse(staticContext.daggerAnvilInteropEnabled)
    assertTrue(dynamicContext.daggerAnvilInteropEnabled)
    assertEquals(
      setOf("LowerService", "HigherService"),
      index.bindingsFor(consumer, index.queryContext(staticContext)!!).mapTo(mutableSetOf()) {
        it.implementationName
      },
    )
    assertEquals(
      listOf("HigherService"),
      index.bindingsFor(consumer, index.queryContext(dynamicContext)!!).map {
        it.implementationName
      },
    )
  }

  fun testRegularDependenciesAreNotRecursivelyVisible() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/IndirectContribution.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        interface Service

        @Inject
        @ContributesBinding(AppScope::class)
        class LibService : Service
        """
          .trimIndent(),
      ) as KtFile
    val indirectGraphFile =
      fixture.addFileToProject(
        "indirectApp/app/IndirectGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*

        @DependencyGraph(AppScope::class)
        interface IndirectGraph
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(indirectGraphFile)
    val contribution = libraryFile.declarationsIncludingNested().klass("LibService")
    val graph = index.graphs.single { it.name == "IndirectGraph" }
    assertTrue(
      index.contributionsForScopes(graph.scopeKeys).any {
        it.pointer.element === contribution
      }
    )

    val queryContext = index.queryContext(index.contextsFor(graph).single())!!
    assertTrue(index.contributionsFor(queryContext).none { it.pointer.element === contribution })
    assertTrue(index.bindingsInContext(queryContext).none { it.pointer.element === contribution })
  }

  fun testInternalHintAvailabilityDoesNotLeakAcrossGraphModules() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/HintAvailabilityBindings.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        interface Service

        @Inject
        @ContributesBinding(AppScope::class)
        class RealService : Service

        class HiddenService : Service

        class ContainerService

        @BindingContainer
        object HiddenContainer {
          @Provides fun containerService(): ContainerService = ContainerService()
        }
        """
          .trimIndent(),
      ) as KtFile
    val friendFile =
      fixture.addFileToProject(
        "app/app/FriendGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.ContainerService
        import lib.Service

        @DependencyGraph(AppScope::class)
        interface FriendGraph {
          val service: Service
          val containerService: ContainerService
        }
        """
          .trimIndent(),
      ) as KtFile
    val unrelatedFile =
      fixture.addFileToProject(
        "bridge/bridge/UnrelatedGraph.kt",
        """
        package bridge

        import dev.zacsweers.metro.*
        import lib.ContainerService
        import lib.Service

        @DependencyGraph(AppScope::class)
        interface UnrelatedGraph {
          val service: Service
          val containerService: ContainerService
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val baseIndex = fixture.project.service<MetroResolutionService>().awaitIndex(friendFile)
    val declarations = libraryFile.declarationsIncludingNested()
    val hiddenService = declarations.klass("HiddenService")
    val hiddenContainer =
      declarations.filterIsInstance<KtObjectDeclaration>().single { it.name == "HiddenContainer" }
    val realServiceId = checkNotNull(declarations.klass("RealService").getClassId())
    val hiddenServiceId = checkNotNull(hiddenService.getClassId())
    val hiddenContainerId = checkNotNull(hiddenContainer.getClassId())
    val friendGraph = baseIndex.graphs.single { it.name == "FriendGraph" }
    val friendModule =
      baseIndex.queryContext(baseIndex.contextsFor(friendGraph).single())!!.graphModule
    // LibraryIndexPostProcessor computes this set with Kotlin's visibility checker. Construct it
    // directly here to isolate the query behavior after one module admits an internal hint.
    val availability = HintAvailability(setOf(friendModule))
    val pointerManager = SmartPointerManager.getInstance(fixture.project)
    val friendService =
      baseIndex.consumerEntryAt(friendFile.declarationsIncludingNested().property("service"))!!
    val hiddenBinding =
      KaBinding.Alias(
        pointer = pointerManager.createSmartPsiElementPointer(hiddenService),
        typeKey = friendService.key,
        consumedKey = null,
        implementationName = "HiddenService",
        originClassId = hiddenServiceId,
        replaces = setOf(realServiceId),
        contributionScopes = friendGraph.scopeKeys,
        isClassContribution = true,
        hintAvailability = availability,
      )
    val restrictedBindings = baseIndex.bindings + hiddenBinding
    val restrictedContributions =
      baseIndex.contributions +
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(hiddenService),
          friendGraph.scopeKeys,
          hiddenServiceId,
          availability,
        ) +
        ContributionEntry(
          pointerManager.createSmartPsiElementPointer(hiddenContainer),
          friendGraph.scopeKeys,
          hiddenContainerId,
          availability,
        )
    val capturedBindingSourceIdentities =
      IdentityHashMap<KaBinding, BindingIndex.SourcePointerIdentity>(restrictedBindings.size)
    val bindingsWithNestedGraphEntries = buildList {
      addAll(restrictedBindings)
      for (graph in baseIndex.graphs) {
        for (contribution in graph.contributedInterfaces) addAll(contribution.bindings)
      }
      for (dynamicGraph in baseIndex.dynamicGraphs) addAll(dynamicGraph.containerInputs)
    }
    for (binding in bindingsWithNestedGraphEntries) {
      val sourceIdentity = sourcePointerIdentity(binding.pointer) ?: continue
      capturedBindingSourceIdentities[binding] = sourceIdentity
    }
    val hiddenBindingSourceIdentity = checkNotNull(capturedBindingSourceIdentities[hiddenBinding])
    val restrictedBuilder =
      BindingIndexBuilder().apply {
        bindings += restrictedBindings
        consumers += baseIndex.consumers
        graphs += baseIndex.graphs
        contributions += restrictedContributions
        assistedSites += baseIndex.assistedSites
        bindingContainers += baseIndex.bindingContainers
        dynamicGraphs += baseIndex.dynamicGraphs
        resolutionInputs = baseIndex.resolutionInputs
        this.capturedBindingSourceIdentities = capturedBindingSourceIdentities
      }
    val restrictedIndex = restrictedBuilder.build()
    capturedBindingSourceIdentities.clear()
    assertEquals(hiddenBindingSourceIdentity, restrictedIndex.sourceIdentityFor(hiddenBinding))

    val unrelatedGraph = restrictedIndex.graphs.single { it.name == "UnrelatedGraph" }
    val friendContext =
      restrictedIndex.queryContext(restrictedIndex.contextsFor(friendGraph).single())!!
    val unrelatedContext =
      restrictedIndex.queryContext(restrictedIndex.contextsFor(unrelatedGraph).single())!!
    val unrelatedService =
      restrictedIndex.consumerEntryAt(
        unrelatedFile.declarationsIncludingNested().property("service")
      )!!
    assertEquals(
      listOf("HiddenService"),
      restrictedIndex.bindingsFor(friendService, friendContext).map { it.implementationName },
    )
    assertEquals(
      listOf("RealService"),
      restrictedIndex.bindingsFor(unrelatedService, unrelatedContext).map { it.implementationName },
    )

    val friendContainerService =
      restrictedIndex.consumerEntryAt(
        friendFile.declarationsIncludingNested().property("containerService")
      )!!
    val unrelatedContainerService =
      restrictedIndex.consumerEntryAt(
        unrelatedFile.declarationsIncludingNested().property("containerService")
      )!!
    assertEquals(1, restrictedIndex.bindingsFor(friendContainerService, friendContext).size)
    assertTrue(restrictedIndex.bindingsFor(unrelatedContainerService, unrelatedContext).isEmpty())
    assertTrue(
      restrictedIndex.contributionsFor(friendContext).any {
        it.classId == hiddenContainerId
      }
    )
    assertTrue(
      restrictedIndex.contributionsFor(unrelatedContext).none {
        it.classId == hiddenContainerId || it.classId == hiddenServiceId
      }
    )
  }

  fun testSameFqnGraphsInUnrelatedModulesKeepTheirOwnAccessors() {
    val appFile =
      fixture.addFileToProject(
        "app/shared/AppGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        @Inject class AppValue

        @DependencyGraph
        interface SharedGraph {
          val appValue: AppValue
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/AppGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        @Inject class BridgeValue

        @DependencyGraph
        interface SharedGraph {
          val bridgeValue: BridgeValue
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val appGraph = index.graphEntryAt(appFile.declarationsIncludingNested().klass("SharedGraph"))!!
    val bridgeGraph =
      index.graphEntryAt(bridgeFile.declarationsIncludingNested().klass("SharedGraph"))!!
    assertEquals(
      listOf("appValue"),
      index.accessorsFor(appGraph).map { (it.pointer.element as KtNamedDeclaration).name },
    )
    assertEquals(
      listOf("bridgeValue"),
      index.accessorsFor(bridgeGraph).map { (it.pointer.element as KtNamedDeclaration).name },
    )

    // The editor action must carry both the caret's compilation and the graph's source identity.
    val explanationContextIds = mutableSetOf<String>()
    val editorChoices =
      listOf(appFile to "appValue", bridgeFile to "bridgeValue").map { (file, accessor) ->
        val fileIndex = fixture.project.service<MetroResolutionService>().awaitIndex(file)
        val offset = file.declarationsIncludingNested().property(accessor).textOffset
        val targets = metroEditorTargets(fileIndex, file, offset, null)
        val choice = targets.navigation.single()
        assertEquals(file.virtualFile, choice.bindings.single().pointer.virtualFile)
        assertEquals(file.virtualFile, targets.reveal.single().path.segments.single().file)
        val explanation = metroBindingExplanations(fileIndex, file, offset, null).single()
        explanationContextIds += explanation.snapshot.context.id
        assertEquals(choice.path, explanation.path)
        assertEquals(
          file.virtualFile,
          explanation.candidates.single { it.selected }.target.pointer.virtualFile,
        )
        choice
      }
    assertEquals(2, editorChoices.map { it.path }.distinct().size)
    assertEquals(2, editorChoices.map { it.text }.distinct().size)
    assertEquals(2, explanationContextIds.size)

    val validationService = fixture.project.service<MetroGraphValidationService>()
    val appResult =
      validationService.validate(appFile, index.contextsFor(appGraph).single()).requireCompleted()
    assertTrue(appResult.diagnostics.joinToString { it.render() }, appResult.diagnostics.isEmpty())
    val bridgeResult =
      validationService
        .validate(bridgeFile, index.contextsFor(bridgeGraph).single())
        .requireCompleted()
    assertTrue(
      bridgeResult.diagnostics.joinToString { it.render() },
      bridgeResult.diagnostics.isEmpty(),
    )
  }

  fun testSameFqnGraphsKeepTheirOwnGenericSupertypeProviders() {
    fixture.addFileToProject(
      "library/shared/GenericBase.kt",
      """
      package shared

      import dev.zacsweers.metro.Provides

      interface GenericBase<T> {
        val value: T

        @Provides fun provideValue(): T = error("unused")
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/shared/Graph.kt",
        """
        package shared

        import dev.zacsweers.metro.DependencyGraph

        @DependencyGraph
        interface SharedGraph : GenericBase<String>
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/Graph.kt",
        """
        package shared

        import dev.zacsweers.metro.DependencyGraph

        @DependencyGraph
        interface SharedGraph : GenericBase<Int>
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val service = fixture.project.service<MetroGraphValidationService>()
    for ((file, expectedType) in listOf(appFile to "kotlin.String", bridgeFile to "kotlin.Int")) {
      val declaration = file.declarationsIncludingNested().klass("SharedGraph")
      val graph = index.graphEntryAt(declaration)!!
      val result = service.validate(file, index.contextsFor(graph).single()).requireCompleted()

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val providedTypes = mutableListOf<String>()
      result.bindings.forEach { key, binding ->
        if (binding is KaBinding.Provided) {
          providedTypes += key.renderedType
        }
      }
      assertEquals(listOf(expectedType), providedTypes)
    }
  }

  fun testUpstreamGenericAssistedFactoryUsesDownstreamBinaryDependencies() {
    fixture.addFileToProject(
      "library/lib/GenericFactory.kt",
      """
      package lib

      import dev.zacsweers.metro.*

      @AssistedInject
      class GenericTarget<T>(@Assisted val id: String, val dependency: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): GenericTarget<T>
        }
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/app/GenericAppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import lib.GenericTarget
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val factory: GenericTarget.Factory<LibClientWithDeps>
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    // The factory's declaration module cannot see the binary dependency; resolution must follow
    // the consuming graph module where its concrete type argument and classpath are available.
    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, index.contextsFor(graph).single())
          .requireCompleted()

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(
        result.bindings.any { key, _ ->
          key.renderedType == "lib.GenericTarget.Factory<libtest.LibClientWithDeps>"
        }
      )
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
    }
  }

  private fun addNestedSourceFactories(): KtFile {
    return fixture.addFileToProject(
      "library/lib/NestedFactories.kt",
      """
      package lib

      import dev.zacsweers.metro.*

      @AssistedInject
      class Inner<T>(@Assisted val id: String, val dependency: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): Inner<T>
        }
      }

      @AssistedInject
      class Outer<T>(@Assisted val id: String, val inner: Inner.Factory<T>) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): Outer<T>
        }
      }
      """
        .trimIndent(),
    ) as KtFile
  }

  private fun assertNestedSourceFactoryChain(
    result: KaGraphValidationResult.Completed,
    factoryFile: KtFile,
  ) {
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factoryTypes = mutableSetOf<String>()
    result.bindings.forEach { key, binding ->
      val isSourceFactory =
        binding is KaBinding.AssistedFactory &&
          binding.pointer.virtualFile == factoryFile.virtualFile
      if (isSourceFactory) {
        factoryTypes += key.renderedType
      }
    }
    assertEquals(
      setOf(
        "lib.Outer.Factory<libtest.LibClientWithDeps>",
        "lib.Inner.Factory<libtest.LibClientWithDeps>",
      ),
      factoryTypes,
    )
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
  }

  fun testNestedUpstreamSourceFactoriesUseDownstreamBinaryDependencies() {
    val factoryFile = addNestedSourceFactories()
    val appFile =
      fixture.addFileToProject(
        "app/app/NestedFactoryGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import lib.Outer
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val factory: Outer.Factory<LibClientWithDeps>
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    // Neither source factory's declaration module has the binary fixture on its classpath.
    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val graph =
        checkNotNull(index.graphEntryAt(appFile.declarationsIncludingNested().klass("AppGraph")))
      val context = index.contextsFor(graph).single()
      assertEquals(appFile.virtualFile, graph.declarationId.file)
      assertEquals(
        KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null),
        checkNotNull(index.queryContext(context)).graphModule,
      )
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, context)
          .requireCompleted()
      assertEquals(graph.declarationId, result.graph.declarationId)
      assertNestedSourceFactoryChain(result, factoryFile)
    }
  }

  fun testIncludedNestedSourceFactoriesUseBothSameFqnGraphModules() {
    val factoryFile = addNestedSourceFactories()
    fixture.addFileToProject(
      "library/lib/NestedFactoryContainer.kt",
      """
      package lib

      import dev.zacsweers.metro.BindingContainer
      import dev.zacsweers.metro.Provides

      @BindingContainer
      abstract class GenericContainer<T> {
        @Provides fun text(factory: Outer.Factory<T>): String = factory.toString()
      }
      """
        .trimIndent(),
    )
    fun addGraph(path: String): KtFile {
      return fixture.addFileToProject(
        path,
        """
        package shared

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes
        import lib.GenericContainer
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface SharedGraph {
          val text: String

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes container: GenericContainer<LibClientWithDeps>): SharedGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    }
    val appFile = addGraph("app/shared/NestedIncludedGraph.kt")
    val bridgeFile = addGraph("bridge/shared/NestedIncludedGraph.kt")
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val bridgeModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(bridgeFile))

    appModule.withMetroLibFixtureLibrary {
      bridgeModule.withMetroLibFixtureLibrary {
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

        fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
        val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
        val includedConsumer =
          index.consumers.single {
            it.includedContainerKey?.renderedType ==
              "lib.GenericContainer<libtest.LibClientWithDeps>" &&
              it.key.renderedType == "lib.Outer.Factory<libtest.LibClientWithDeps>"
          }
        val owners =
          checkNotNull(
            ConsumerOwnershipBundle.build(index).includedContainerPointers(includedConsumer)
          )
        assertEquals(
          setOf(appModule, bridgeModule),
          owners.mapTo(mutableSetOf()) { pointer ->
            ModuleUtilCore.findModuleForPsiElement(checkNotNull(pointer.element))
          },
        )

        val validation = fixture.project.service<MetroGraphValidationService>()
        val graphIds = mutableSetOf<GraphDeclarationId>()
        for (file in listOf(appFile, bridgeFile)) {
          val graph =
            checkNotNull(
              index.graphEntryAt(file.declarationsIncludingNested().klass("SharedGraph"))
            )
          graphIds += graph.declarationId
          assertEquals(file.virtualFile, graph.declarationId.file)
          val context = index.contextsFor(graph).single()
          assertEquals(
            KaModuleProvider.getModule(fixture.project, file, useSiteModule = null),
            checkNotNull(index.queryContext(context)).graphModule,
          )
          val result = validation.validate(file, context).requireCompleted()
          assertEquals(graph.declarationId, result.graph.declarationId)
          assertNestedSourceFactoryChain(result, factoryFile)
        }
        assertEquals(2, graphIds.size)
      }
    }
  }

  fun testBinaryGenericFactoryContinuesThroughNestedSourceFactories() {
    val factoryFile = addNestedSourceFactories()
    val appFile =
      fixture.addFileToProject(
        "app/app/BinaryToSourceFactoryGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import lib.Outer
        import libtest.LibClientWithDeps
        import libtest.LibGenericAssistedExample

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val factory: LibGenericAssistedExample.Factory<Outer.Factory<LibClientWithDeps>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val graph =
        checkNotNull(index.graphEntryAt(appFile.declarationsIncludingNested().klass("AppGraph")))
      val context = index.contextsFor(graph).single()
      assertEquals(appFile.virtualFile, graph.declarationId.file)
      assertEquals(
        KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null),
        checkNotNull(index.queryContext(context)).graphModule,
      )
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, context)
          .requireCompleted()
      assertEquals(graph.declarationId, result.graph.declarationId)
      assertTrue(
        result.bindings.any { key, binding ->
          binding is KaBinding.AssistedFactory &&
            key.renderedType ==
              "libtest.LibGenericAssistedExample.Factory<lib.Outer.Factory<libtest.LibClientWithDeps>>"
        }
      )
      assertNestedSourceFactoryChain(result, factoryFile)
    }
  }

  fun testBinarySourceFactoryRoundTripIgnoresIsolatedSameFqnSourceFactory() {
    val shadowFile =
      fixture.addFileToProject(
        "bridge/libtest/LibGenericAssistedExample.kt",
        """
        package libtest

        import dev.zacsweers.metro.*

        @AssistedInject
        class LibGenericAssistedExample<T>(@Assisted val inputT: T, val graphT: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(inputT: T): LibGenericAssistedExample<T>
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/FactoryRoundTripGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import libtest.LibClientWithDeps
        import libtest.LibGenericAssistedExample

        @AssistedInject
        class Outer<T>(
          @Assisted val id: String,
          val dependency: LibGenericAssistedExample.Factory<T>,
        ) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Outer<T>
          }
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val factory: LibGenericAssistedExample.Factory<Outer.Factory<LibClientWithDeps>>
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val shadowModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(shadowFile))
    assertFalse(appModule == shadowModule)

    // The app sees the binary factory, not the isolated same-FQN source declaration. Outer is
    // discovered through the binary root, so its dependency must return to binary resolution.
    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val sourceFactoryType = "app.Outer.Factory<libtest.LibClientWithDeps>"
      assertTrue(index.consumers.none { it.key.renderedType == sourceFactoryType })
      assertTrue(
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().any {
          it.originClassId?.asFqNameString() == "libtest.LibGenericAssistedExample.Factory" &&
            it.pointer.virtualFile == shadowFile.virtualFile
        }
      )

      val graph =
        checkNotNull(index.graphEntryAt(appFile.declarationsIncludingNested().klass("AppGraph")))
      val context = index.contextsFor(graph).single()
      assertEquals(appFile.virtualFile, graph.declarationId.file)
      assertEquals(
        KaModuleProvider.getModule(fixture.project, appFile, useSiteModule = null),
        checkNotNull(index.queryContext(context)).graphModule,
      )
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, context)
          .requireCompleted()
      assertEquals(graph.declarationId, result.graph.declarationId)
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(
        result.bindings.any { key, binding ->
          key.renderedType == sourceFactoryType &&
            binding is KaBinding.AssistedFactory &&
            binding.pointer.virtualFile == appFile.virtualFile
        }
      )

      val binaryFactoryTypes = mutableSetOf<String>()
      result.bindings.forEach { key, binding ->
        if (
          binding is KaBinding.AssistedFactory &&
            binding.originClassId?.asFqNameString() == "libtest.LibGenericAssistedExample.Factory"
        ) {
          assertNotNull(binding.pointer.virtualFile)
          assertFalse(binding.pointer.virtualFile == shadowFile.virtualFile)
          binaryFactoryTypes += key.renderedType
        }
      }
      assertEquals(
        setOf(
          "libtest.LibGenericAssistedExample.Factory<$sourceFactoryType>",
          "libtest.LibGenericAssistedExample.Factory<libtest.LibClientWithDeps>",
        ),
        binaryFactoryTypes,
      )
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testSameFqnOrdinaryFactoryDoesNotAcquireAssistedFactoryBinding() {
    fun addGraph(path: String, factoryAnnotation: String): KtFile {
      return fixture.addFileToProject(
        path,
        """
        package shared

        import dev.zacsweers.metro.*

        @AssistedInject
        class Widget<T>(@Assisted val id: String, val dependency: T) {
          $factoryAnnotation
          fun interface Factory<T> {
            fun create(id: String): Widget<T>
          }
        }

        @DependencyGraph
        interface SharedGraph {
          val factory: Widget.Factory<Int>

          @Provides fun number(): Int = 1
        }
        """
          .trimIndent(),
      ) as KtFile
    }
    val annotatedFile = addGraph("app/shared/FactoryGraph.kt", "@AssistedFactory")
    val ordinaryFile = addGraph("bridge/shared/FactoryGraph.kt", "")
    val settings = MetroSettings.getInstance(fixture.project).state
    val previousResolveFromLibraries = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      fixture.project.service<MetroResolutionService>().awaitIndex(ordinaryFile)
      val index = fixture.project.service<MetroResolutionService>().awaitIndex(annotatedFile)
      val annotatedGraph =
        checkNotNull(
          index.graphEntryAt(annotatedFile.declarationsIncludingNested().klass("SharedGraph"))
        )
      val ordinaryGraph =
        checkNotNull(
          index.graphEntryAt(ordinaryFile.declarationsIncludingNested().klass("SharedGraph"))
        )
      assertEquals(annotatedFile.virtualFile, annotatedGraph.declarationId.file)
      assertEquals(ordinaryFile.virtualFile, ordinaryGraph.declarationId.file)
      assertFalse(annotatedGraph.declarationId == ordinaryGraph.declarationId)

      val concreteFactories =
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().filter {
          it.typeKey.renderedType == "shared.Widget.Factory<kotlin.Int>"
        }
      assertTrue(concreteFactories.isNotEmpty())
      assertTrue(concreteFactories.all { it.pointer.virtualFile == annotatedFile.virtualFile })
      assertTrue(
        index.bindings.filterIsInstance<KaBinding.AssistedFactory>().none {
          it.pointer.virtualFile == ordinaryFile.virtualFile
        }
      )

      val ordinaryContext = index.contextsFor(ordinaryGraph).single()
      assertEquals(
        KaModuleProvider.getModule(fixture.project, ordinaryFile, useSiteModule = null),
        checkNotNull(index.queryContext(ordinaryContext)).graphModule,
      )
      val ordinaryConsumer =
        checkNotNull(
          index.consumerEntryAt(ordinaryFile.declarationsIncludingNested().property("factory"))
        )
      val ordinaryResolution = index.resolveConsumer(ordinaryConsumer)
      assertEquals(
        ordinaryContext.path,
        ordinaryResolution.perContext.keys.single().path,
      )
      assertTrue(ordinaryResolution.candidateBindings.isEmpty())

      val validation = fixture.project.service<MetroGraphValidationService>()
      val annotatedResult =
        validation
          .validate(annotatedFile, index.contextsFor(annotatedGraph).single())
          .requireCompleted()
      assertTrue(
        annotatedResult.diagnostics.joinToString { it.render() },
        annotatedResult.diagnostics.isEmpty(),
      )
      val ordinaryResult = validation.validate(ordinaryFile, ordinaryContext).requireCompleted()
      assertEquals(
        listOf(MetroDiagnosticId.MISSING_BINDING),
        ordinaryResult.diagnostics.map { it.id },
      )
    } finally {
      settings.resolveFromLibraries = previousResolveFromLibraries
    }
  }

  fun testInheritedGenericFactoryRequestsUseTheirExactSameFqnGraphModule() {
    fixture.addFileToProject(
      "library/shared/GenericFactoryBase.kt",
      """
      package shared

      import dev.zacsweers.metro.*

      @AssistedInject
      class GenericTarget<T>(@Assisted val id: String, val dependency: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): GenericTarget<T>
        }
      }

      interface GenericBase<T> {
        val factory: GenericTarget.Factory<T>
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/shared/InheritedGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface SharedGraph : GenericBase<LibClientWithDeps>
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/InheritedGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Inject

        @Inject class BridgeDependency

        @DependencyGraph(AppScope::class)
        interface SharedGraph : GenericBase<BridgeDependency>
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    // The same graph FQN is declared in both modules; only the app can see the binary dependency.
    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val validation = fixture.project.service<MetroGraphValidationService>()
      val appGraph =
        checkNotNull(index.graphEntryAt(appFile.declarationsIncludingNested().klass("SharedGraph")))
      val appResult =
        validation.validate(appFile, index.contextsFor(appGraph).single()).requireCompleted()
      assertTrue(
        appResult.diagnostics.joinToString { it.render() },
        appResult.diagnostics.isEmpty(),
      )
      assertTrue(
        appResult.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" }
      )
      assertTrue(appResult.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })

      val bridgeGraph =
        checkNotNull(
          index.graphEntryAt(bridgeFile.declarationsIncludingNested().klass("SharedGraph"))
        )
      val bridgeResult =
        validation.validate(bridgeFile, index.contextsFor(bridgeGraph).single()).requireCompleted()
      assertTrue(
        bridgeResult.diagnostics.joinToString { it.render() },
        bridgeResult.diagnostics.isEmpty(),
      )
      assertTrue(
        bridgeResult.bindings.any { key, _ -> key.renderedType == "shared.BridgeDependency" }
      )
      assertFalse(
        bridgeResult.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" }
      )
    }
  }

  fun testInheritedBinaryFactoryAndInjectAccessorsUseTheOwningGraphModule() {
    fixture.addFileToProject(
      "library/lib/AccessorBases.kt",
      """
      package lib

      interface InjectedBase<T> {
        val dependency: T
      }

      interface FactoryBase<T> {
        val factory: T
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/app/InheritedBinaryGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Provides
        import lib.FactoryBase
        import lib.InjectedBase
        import libtest.LibClientWithDeps
        import libtest.LibGenericAssistedExample

        @DependencyGraph(AppScope::class)
        interface AppGraph :
          InjectedBase<LibClientWithDeps>,
          FactoryBase<LibGenericAssistedExample.Factory<Int>> {
          @Provides fun number(): Int = 1
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, index.contextsFor(graph).single())
          .requireCompleted()

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
      assertTrue(
        result.bindings.any { key, _ ->
          key.renderedType == "libtest.LibGenericAssistedExample.Factory<kotlin.Int>"
        }
      )
    }
  }

  fun testInheritedProviderParametersAndReceiversUseTheOwningGraphModule() {
    fixture.addFileToProject(
      "library/lib/InheritedProviders.kt",
      """
      package lib

      import dev.zacsweers.metro.*

      @AssistedInject
      class GenericTarget<T>(@Assisted val id: String, val dependency: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): GenericTarget<T>
        }
      }

      interface ParameterService
      interface ReceiverService

      interface ParameterProviders<T> {
        @Provides fun parameterService(factory: GenericTarget.Factory<T>): ParameterService =
          object : ParameterService {}
      }

      interface ReceiverProviders<T> {
        @Provides fun GenericTarget.Factory<T>.receiverService(): ReceiverService =
          object : ReceiverService {}
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/app/InheritedProviderGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import lib.ParameterProviders
        import lib.ParameterService
        import lib.ReceiverProviders
        import lib.ReceiverService
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface AppGraph :
          ParameterProviders<LibClientWithDeps>,
          ReceiverProviders<LibClientWithDeps> {
          val parameterService: ParameterService
          val receiverService: ReceiverService
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))

    appModule.withMetroLibFixtureLibrary {
      PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
      IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

      val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val result =
        fixture.project
          .service<MetroGraphValidationService>()
          .validate(appFile, index.contextsFor(graph).single())
          .requireCompleted()

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testIncludedGenericContainerConsumersUseEveryOwningGraphModule() {
    fixture.addFileToProject(
      "library/lib/GenericContainer.kt",
      """
      package lib

      import dev.zacsweers.metro.BindingContainer
      import dev.zacsweers.metro.Provides

      @BindingContainer
      abstract class GenericContainer<T> {
        @Provides fun text(dependency: T): String = dependency.toString()
      }
      """
        .trimIndent(),
    )
    val appFile =
      fixture.addFileToProject(
        "app/app/IncludedGraph.kt",
        """
        package app

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes
        import lib.GenericContainer
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val text: String

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes container: GenericContainer<LibClientWithDeps>): AppGraph
          }
        }

        @DependencyGraph(AppScope::class)
        interface OtherAppGraph {
          val text: String

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes container: GenericContainer<LibClientWithDeps>): OtherAppGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/bridge/IncludedGraph.kt",
        """
        package bridge

        import dev.zacsweers.metro.AppScope
        import dev.zacsweers.metro.DependencyGraph
        import dev.zacsweers.metro.Includes
        import lib.GenericContainer
        import libtest.LibClientWithDeps

        @DependencyGraph(AppScope::class)
        interface BridgeGraph {
          val text: String

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes container: GenericContainer<LibClientWithDeps>): BridgeGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val bridgeModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(bridgeFile))

    appModule.withMetroLibFixtureLibrary {
      bridgeModule.withMetroLibFixtureLibrary {
        PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
        IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

        fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
        val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
        val includedConsumer =
          index.consumers.single {
            it.includedContainerKey?.renderedType ==
              "lib.GenericContainer<libtest.LibClientWithDeps>" &&
              it.key.renderedType == "libtest.LibClientWithDeps"
          }
        val owners =
          checkNotNull(
            ConsumerOwnershipBundle.build(index).includedContainerPointers(includedConsumer)
          )
        assertEquals("Two graphs in the app module should share one owner", 2, owners.size)
        val ownerModules =
          owners.mapTo(mutableSetOf()) { pointer ->
            ModuleUtilCore.findModuleForPsiElement(checkNotNull(pointer.element))
          }
        assertEquals(setOf(appModule, bridgeModule), ownerModules)

        val validation = fixture.project.service<MetroGraphValidationService>()
        val graphDeclarations =
          listOf(appFile to "AppGraph", appFile to "OtherAppGraph", bridgeFile to "BridgeGraph")
        for ((file, name) in graphDeclarations) {
          val graph =
            checkNotNull(index.graphEntryAt(file.declarationsIncludingNested().klass(name)))
          val result =
            validation.validate(file, index.contextsFor(graph).single()).requireCompleted()
          assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
          assertTrue(
            result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" }
          )
          assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
        }
      }
    }
  }

  fun testSameFqnGraphsReportOnlyTheirOwnLazyAssistedFactorySites() {
    fun addGraph(path: String): KtFile {
      return fixture.addFileToProject(
        path,
        """
        package shared

        import dev.zacsweers.metro.*

        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          fun interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject class Consumer(val factory: Lazy<Widget.Factory>)

        @DependencyGraph
        interface SharedGraph {
          val consumer: Consumer
        }
        """
          .trimIndent(),
      ) as KtFile
    }

    val appFile = addGraph("app/shared/LazyGraphs.kt")
    val bridgeFile = addGraph("bridge/shared/LazyGraphs.kt")
    checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile)).addKotlinStdlibLibrary()
    checkNotNull(ModuleUtilCore.findModuleForPsiElement(bridgeFile)).addKotlinStdlibLibrary()
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val validation = fixture.project.service<MetroGraphValidationService>()
    for (file in listOf(appFile, bridgeFile)) {
      val declaration = file.declarationsIncludingNested().klass("SharedGraph")
      val graph = checkNotNull(index.graphEntryAt(declaration))
      val result = validation.validate(file, index.contextsFor(graph).single()).requireCompleted()
      val diagnostic = result.diagnostics.single()

      assertEquals(MetroDiagnosticId.INVALID_BINDING, diagnostic.id)
      assertTrue(
        diagnostic.stack.joinToString(),
        diagnostic.stack.mapNotNull { it.pointer?.virtualFile }.all { it == file.virtualFile },
      )
      assertTrue(
        diagnostic.related.joinToString { it.typeKey.renderedType },
        diagnostic.related.all { it.pointer.virtualFile == file.virtualFile },
      )
    }
  }

  fun testSameFqnExtensionsInUnrelatedModulesKeepTheirOwnParents() {
    val appFile =
      fixture.addFileToProject(
        "app/shared/ExtensionGraphs.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        @Inject class AppValue

        @GraphExtension
        interface ChildGraph {
          val appValue: AppValue
        }

        @DependencyGraph
        interface ParentGraph {
          val child: ChildGraph
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/ExtensionGraphs.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        @Inject class BridgeValue

        @GraphExtension
        interface ChildGraph {
          val bridgeValue: BridgeValue
        }

        @DependencyGraph
        interface ParentGraph {
          val child: ChildGraph
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val appDeclarations = appFile.declarationsIncludingNested()
    val bridgeDeclarations = bridgeFile.declarationsIncludingNested()
    val appParent = index.graphEntryAt(appDeclarations.klass("ParentGraph"))!!
    val bridgeParent = index.graphEntryAt(bridgeDeclarations.klass("ParentGraph"))!!
    val appChild = index.graphEntryAt(appDeclarations.klass("ChildGraph"))!!
    val bridgeChild = index.graphEntryAt(bridgeDeclarations.klass("ChildGraph"))!!

    assertEquals(listOf(appChild), index.extensionsOf(appParent))
    assertEquals(listOf(bridgeChild), index.extensionsOf(bridgeParent))
    assertEquals(
      listOf(appChild, appParent),
      index.contextsFor(appChild).single().chain,
    )
    assertEquals(
      listOf(bridgeChild, bridgeParent),
      index.contextsFor(bridgeChild).single().chain,
    )
  }

  fun testSameFqnFactoryInputsInUnrelatedModulesKeepTheirOwnMembers() {
    val appFile =
      fixture.addFileToProject(
        "app/shared/FactoryGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        interface AppValue
        interface ExternalDependencies {
          val appValue: AppValue
        }

        @DependencyGraph
        interface FactoryGraph {
          val appValue: AppValue

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes dependencies: ExternalDependencies): FactoryGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/FactoryGraph.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        interface BridgeValue
        interface ExternalDependencies {
          val bridgeValue: BridgeValue
        }

        @DependencyGraph
        interface FactoryGraph {
          val bridgeValue: BridgeValue

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes dependencies: ExternalDependencies): FactoryGraph
          }
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(bridgeFile)
    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val validationService = fixture.project.service<MetroGraphValidationService>()
    val appGraph = index.graphEntryAt(appFile.declarationsIncludingNested().klass("FactoryGraph"))!!
    val bridgeGraph =
      index.graphEntryAt(bridgeFile.declarationsIncludingNested().klass("FactoryGraph"))!!
    val appResult = validationService.validate(appFile, index.contextsFor(appGraph).single())
    val bridgeResult =
      validationService.validate(bridgeFile, index.contextsFor(bridgeGraph).single())

    assertTrue(appResult.requireCompleted().diagnostics.isEmpty())
    assertTrue(bridgeResult.requireCompleted().diagnostics.isEmpty())
  }

  fun testExtensionsUseTheirDeclarationModuleOptions() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibExtension.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        @Inject class ChildValue

        @GraphExtension
        interface LibExtension {
          val childProvider: () -> ChildValue
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.LibExtension

        @DependencyGraph
        interface AppGraph {
          val extension: LibExtension
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions("enable-function-providers" to "true")
    libraryModule.setModuleMetroOptions("enable-function-providers" to "false")
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val resolutionService = fixture.project.service<MetroResolutionService>()
    val appIndex = resolutionService.awaitIndex(appFile)
    assertNotSame(appIndex, resolutionService.awaitIndex(libraryFile))
    val appGraph = appIndex.graphEntryAt(appFile.declarationsIncludingNested().klass("AppGraph"))!!
    val results =
      fixture.project
        .service<MetroGraphValidationService>()
        .validateWithExtensions(appFile, appGraph)

    assertEquals(listOf("LibExtension", "AppGraph"), results.map { it.graph.name })
    assertEquals(
      listOf(MetroDiagnosticId.MISSING_BINDING),
      results.first().requireCompleted().diagnostics.map { it.id },
    )
    assertTrue(results.last().requireCompleted().diagnostics.isEmpty())
  }

  fun testManualRefreshPublishesDifferentOptionKeysAsOneGeneration() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibraryGraph.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        @Inject class OriginalLibraryService

        @DependencyGraph
        interface LibraryGraph {
          val service: OriginalLibraryService
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*

        @Inject class OriginalAppService

        @DependencyGraph
        interface AppGraph {
          val service: OriginalAppService
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions("enable-function-providers" to "true")
    libraryModule.setModuleMetroOptions("enable-function-providers" to "false")
    for (otherModule in ModuleManager.getInstance(fixture.project).modules) {
      if (otherModule != appModule && otherModule != libraryModule) {
        otherModule.setModuleMetroOptions("enabled" to "false")
      }
    }
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val service = fixture.project.service<MetroResolutionService>()
    // Wait for both option keys before reading the shared presentation generation.
    service.awaitIndex(appFile)
    service.awaitIndex(libraryFile)
    val initialApp = service.presentationIndex(appFile)
    val initialLibrary = service.presentationIndex(libraryFile)
    assertNotSame(initialApp, initialLibrary)
    assertSame(initialApp.generationToken, initialLibrary.generationToken)
    val settings = MetroSettings.getInstance(fixture.project).state

    try {
      settings.automaticallyRefreshGraphData = false
      service.settingsChanged()

      val documents = PsiDocumentManager.getInstance(fixture.project)
      val appDocument = checkNotNull(documents.getDocument(appFile))
      val libraryDocument = checkNotNull(documents.getDocument(libraryFile))
      runInEdtAndWait {
        WriteCommandAction.runWriteCommandAction(fixture.project) {
          appDocument.insertString(appDocument.textLength, "\n\n@Inject class AddedAppService")
          libraryDocument.insertString(
            libraryDocument.textLength,
            "\n\n@Inject class AddedLibraryService",
          )
        }
        documents.commitAllDocuments()
      }

      assertSame(initialApp, service.presentationIndex(appFile))
      assertSame(initialLibrary, service.presentationIndex(libraryFile))

      val refreshStarted = AtomicBoolean()
      val inconsistentListenerSnapshot = AtomicBoolean()
      val refreshed = CompletableFuture<Pair<BindingIndex, BindingIndex>>()
      service.addIndexListener(testRootDisposable) {
        if (!refreshStarted.get()) return@addIndexListener
        val appPresentation = service.presentationIndex(appFile)
        val libraryPresentation = service.presentationIndex(libraryFile)
        val appChanged = appPresentation !== initialApp
        val libraryChanged = libraryPresentation !== initialLibrary
        val appContainsEdit =
          appPresentation.bindings.any { it.typeKey.renderedType == "app.AddedAppService" }
        val libraryContainsEdit =
          libraryPresentation.bindings.any {
            it.typeKey.renderedType == "lib.AddedLibraryService"
          }
        val completePublication =
          appChanged &&
            libraryChanged &&
            appPresentation.generationToken === libraryPresentation.generationToken &&
            appContainsEdit &&
            libraryContainsEdit
        if ((appChanged || libraryChanged) && !completePublication) {
          inconsistentListenerSnapshot.set(true)
        }
        if (completePublication) {
          refreshed.complete(appPresentation to libraryPresentation)
        }
      }

      refreshStarted.set(true)
      service.refreshGraphData()
      PlatformTestUtil.waitForFuture(refreshed, 30_000)
      val (refreshedApp, refreshedLibrary) = refreshed.join()

      assertFalse(inconsistentListenerSnapshot.get())
      assertNotSame(initialApp, refreshedApp)
      assertNotSame(initialLibrary, refreshedLibrary)
      assertTrue(refreshedApp.bindings.any { it.typeKey.renderedType == "app.AddedAppService" })
      assertTrue(
        refreshedLibrary.bindings.any {
          it.typeKey.renderedType == "lib.AddedLibraryService"
        }
      )
      assertSame(refreshedApp.generationToken, refreshedLibrary.generationToken)
    } finally {
      settings.automaticallyRefreshGraphData = true
      service.settingsChanged()
    }
  }

  fun testParentSuspendResolutionUsesParentOptionsButRejectsDisabledChild() {
    val libraryFile =
      fixture.addFileToProject(
        "library/lib/LibExtension.kt",
        """
        package lib

        import dev.zacsweers.metro.*

        abstract class CacheScope private constructor()

        class Database

        @SingleIn(CacheScope::class)
        @Inject class Cache(val database: Database)

        @GraphExtension
        interface LibExtension {
          val cache: Cache
        }
        """
          .trimIndent(),
      ) as KtFile
    val appFile =
      fixture.addFileToProject(
        "app/app/AppGraph.kt",
        """
        package app

        import dev.zacsweers.metro.*
        import lib.CacheScope
        import lib.Database
        import lib.LibExtension

        @SingleIn(CacheScope::class)
        @DependencyGraph
        interface AppGraph {
          val extension: LibExtension

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
          .trimIndent(),
      ) as KtFile
    val appModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(appFile))
    val libraryModule = checkNotNull(ModuleUtilCore.findModuleForPsiElement(libraryFile))
    appModule.setModuleMetroOptions("enable-suspend-providers" to "true")
    libraryModule.setModuleMetroOptions("enable-suspend-providers" to "false")
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    fixture.project.service<MetroResolutionService>().awaitIndex(libraryFile)
    val appIndex = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val extension = appIndex.graphs.single { it.name == "LibExtension" }
    val context = appIndex.contextsFor(extension).single { it.chain.last().name == "AppGraph" }
    val result =
      fixture.project
        .service<MetroGraphValidationService>()
        .validate(libraryFile, context)
        .requireCompleted()

    assertEquals(
      listOf(MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED),
      result.diagnostics.map { it.id },
    )
  }

  fun testSameFqnBindingContainersKeepTheirOwnTransitiveIncludes() {
    val appFile =
      fixture.addFileToProject(
        "app/shared/Containers.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        class AppValue

        @BindingContainer
        object AppIncluded {
          @Provides fun appValue(): AppValue = AppValue()
        }

        @BindingContainer(includes = [AppIncluded::class])
        object SharedContainer

        @DependencyGraph(bindingContainers = [SharedContainer::class])
        interface AppGraph {
          val appValue: AppValue
        }
        """
          .trimIndent(),
      ) as KtFile
    val bridgeFile =
      fixture.addFileToProject(
        "bridge/shared/Containers.kt",
        """
        package shared

        import dev.zacsweers.metro.*

        class BridgeValue

        @BindingContainer
        object BridgeIncluded {
          @Provides fun bridgeValue(): BridgeValue = BridgeValue()
        }

        @BindingContainer(includes = [BridgeIncluded::class])
        object SharedContainer

        @DependencyGraph(bindingContainers = [SharedContainer::class])
        interface BridgeGraph {
          val bridgeValue: BridgeValue
        }
        """
          .trimIndent(),
      ) as KtFile
    PsiDocumentManager.getInstance(fixture.project).commitAllDocuments()
    IndexingTestUtil.waitUntilIndexesAreReady(fixture.project)

    val index = fixture.project.service<MetroResolutionService>().awaitIndex(appFile)
    val appAccessor =
      index.consumerEntryAt(appFile.declarationsIncludingNested().property("appValue"))!!
    val bridgeAccessor =
      index.consumerEntryAt(bridgeFile.declarationsIncludingNested().property("bridgeValue"))!!
    assertEquals(
      listOf("appValue"),
      index.resolveConsumer(appAccessor).uniformBindings.orEmpty().mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
    assertEquals(
      listOf("bridgeValue"),
      index.resolveConsumer(bridgeAccessor).uniformBindings.orEmpty().mapNotNull {
        (it.pointer.element as? KtNamedDeclaration)?.name
      },
    )
  }
}

private fun Module.setModuleMetroOptions(vararg options: Pair<String, String>) {
  val facetManager = FacetManager.getInstance(this)
  val facetModel = facetManager.createModifiableModel()
  val configuration = KotlinFacetBridgeFactory.createFacetConfiguration()
  configuration.settings.initializeIfNeeded(this, null)
  configuration.settings.useProjectSettings = false
  configuration.settings.updateCompilerArguments {
    val configuredOptions =
      if (options.any { (name, _) -> name == "enabled" }) {
        options.toList()
      } else {
        listOf("enabled" to "true") + options
      }
    pluginOptions =
      configuredOptions.map { (name, value) -> "plugin:$PLUGIN_ID:$name=$value" }.toTypedArray()
  }
  val facet = facetManager.createFacet(KotlinFacetType.INSTANCE, "Kotlin", configuration, null)
  facetModel.addFacet(facet)
  runInEdtAndWait { runWriteAction { facetModel.commit() } }
}
