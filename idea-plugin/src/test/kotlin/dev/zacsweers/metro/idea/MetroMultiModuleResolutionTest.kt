// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.facet.FacetManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.HintAvailability
import dev.zacsweers.metro.idea.model.KaBinding
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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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

    val index = fixture.project.service<MetroResolutionService>().index(indirectGraphFile)
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

    val baseIndex = fixture.project.service<MetroResolutionService>().index(friendFile)
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
    val restrictedIndex =
      BindingIndex(
        bindings = baseIndex.bindings + hiddenBinding,
        consumers = baseIndex.consumers,
        graphs = baseIndex.graphs,
        contributions =
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
            ),
        assistedSites = baseIndex.assistedSites,
        bindingContainers = baseIndex.bindingContainers,
      )

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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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

      val index = fixture.project.service<MetroResolutionService>().index(appFile)
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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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
    val appIndex = resolutionService.index(appFile)
    assertNotSame(appIndex, resolutionService.index(libraryFile))
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

    val appIndex = fixture.project.service<MetroResolutionService>().index(appFile)
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

    val index = fixture.project.service<MetroResolutionService>().index(appFile)
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
