// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import dev.zacsweers.metro.idea.index.MetroResolutionService
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

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

    assertEquals(
      listOf("provideService"),
      resolution.effective.map { (it.pointer.element as? KtNamedDeclaration)?.name },
    )
    assertEquals(
      setOf("AppGraph", "LibExtension"),
      resolution.perContext.keys.mapTo(mutableSetOf()) { it.graph.name },
    )
    assertTrue(resolution.perContext.keys.none { it.graph.name == "LibGraph" })

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
      extensionResolution.effective.map {
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
}
