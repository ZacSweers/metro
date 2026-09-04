// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchRequestCollector
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.SearchSession
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usages.Usage
import com.intellij.usages.impl.rules.UsageWithType
import com.intellij.usages.rules.PsiElementUsage
import com.intellij.util.Processor
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.usages.MetroFindUsagesHandlerFactory
import dev.zacsweers.metro.idea.usages.MetroUsageSearcher
import dev.zacsweers.metro.idea.usages.collectMetroUsages
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtParameter

class MetroFindUsagesTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<GraphContextPinService>().clear()
  }

  override fun tearDown() {
    try {
      project.service<GraphContextPinService>().clear()
    } finally {
      super.tearDown()
    }
  }

  fun testProviderAndConsumerRelationshipsAreBidirectional() {
    val file =
      myFixture.configureMetroFile(
        """
        class Dependency
        interface Service

        @Inject
        class Consumer(
          val dependency: Dependency,
          val service: Service,
        )

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
          val dependency: Dependency

          @Provides fun provideDependency(): Dependency = Dependency()
          @Provides fun provideService(dependency: Dependency): Service = object : Service {}
        }
        """
      )
    val declarations = file.declarationsIncludingNested()
    val provideDependency = declarations.function("provideDependency")

    assertEquals(
      setOf(
        FoundUsage("Consumer.dependency", "Injected at"),
        FoundUsage("AppGraph.dependency", "Injected at"),
        FoundUsage("provideService.dependency", "Injected at"),
      ),
      metroUsages(provideDependency).toSet(),
    )

    val serviceParameter =
      declarations.filterIsInstance<KtParameter>().single {
        it.name == "service" && it.ownerName() == "Consumer"
      }
    assertEquals(
      listOf(FoundUsage("AppGraph.provideService", "Provided by")),
      metroUsages(serviceParameter),
    )
  }

  fun testFindUsagesCapabilityCheckUsesDefaultMetroSyntaxOnEdt() {
    val file =
      myFixture.configureByText(
        "Capability.kt",
        """
        import dev.zacsweers.metro.Inject

        @Deprecated("test")
        class Annotated {
          fun deprecatedMember() = Unit
        }

        @Inject
        class MetroAnnotated {
          fun metroMember() = Unit
        }

        fun plain() = Unit
        """
          .trimIndent(),
      )
    project.clearMetroOptions()
    val declarations = (file as KtFile).declarationsIncludingNested()
    val factory = MetroFindUsagesHandlerFactory()

    runInEdtAndWait {
      assertFalse(factory.canFindUsages(declarations.function("deprecatedMember")))
      assertTrue(factory.canFindUsages(declarations.function("metroMember")))
      assertFalse(factory.canFindUsages(declarations.function("plain")))
    }
  }

  fun testFindUsagesCapabilityCheckUsesCachedCustomMetroSyntaxOnEdt() {
    project.setMetroOptions("custom-inject" to "test/CustomInject")
    val file =
      myFixture.configureByText(
        "CustomCapability.kt",
        """
        package test

        annotation class CustomInject

        @CustomInject
        class CustomAnnotated {
          fun customMember() = Unit
        }
        """
          .trimIndent(),
      ) as KtFile
    val customMember = file.declarationsIncludingNested().function("customMember")
    project.service<MetroIdeProjectService>().state(customMember)
    val factory = MetroFindUsagesHandlerFactory()

    runInEdtAndWait {
      assertTrue(factory.canFindUsages(customMember))
    }
  }

  fun testBindsAndMultibindingDeclarationsFindAggregateConsumers() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        @Inject class ServiceImpl : Service

        interface Bindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        interface Plugin
        @Inject
        @ContributesIntoSet(AppScope::class)
        class DebugPlugin : Plugin

        @DependencyGraph(AppScope::class, bindingContainers = [Bindings::class])
        interface AppGraph {
          val service: Service
          val plugins: Set<Plugin>
        }
        """
      )
    val declarations = file.declarationsIncludingNested()

    assertEquals(
      listOf(FoundUsage("AppGraph.service", "Injected at")),
      metroUsages(declarations.function("bindService")),
    )
    assertEquals(
      listOf(FoundUsage("AppGraph.plugins", "Injected at")),
      metroUsages(declarations.klass("DebugPlugin")),
    )
  }

  fun testPinnedGraphFiltersRelationshipsAndClearingRestoresTheirUnion() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service

        @DependencyGraph
        interface FirstGraph {
          val service: Service
        }

        @DependencyGraph
        interface SecondGraph {
          val service: Service
        }
        """
      )
    val declarations = file.declarationsIncludingNested()
    val service = declarations.klass("Service")

    assertEquals(
      setOf(
        FoundUsage("FirstGraph.service", "Injected at"),
        FoundUsage("SecondGraph.service", "Injected at"),
      ),
      metroUsages(service).toSet(),
    )

    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val firstGraph = index.graphs.single { it.name == "FirstGraph" }
    project.service<GraphContextPinService>().pin(index.contextsFor(firstGraph).single().path)
    assertEquals(
      listOf(FoundUsage("FirstGraph.service", "Injected at")),
      metroUsages(service),
    )

    project.service<GraphContextPinService>().clear()
    assertEquals(2, metroUsages(service).size)
  }

  fun testKotlinUsagesAreMergedIntoTheMetroUsageGroup() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class <caret>ServiceImpl
        @Inject class Consumer(val service: ServiceImpl)
        """
      )
    val service = file.declarationsIncludingNested().klass("ServiceImpl")

    val usageTree = myFixture.getUsageViewTreeTextRepresentation(service)

    assertTrue(usageTree, "Injected at" in usageTree)
    assertEquals(
      usageTree,
      1,
      Regex("service: ServiceImpl").findAll(usageTree).count(),
    )
  }

  fun testCustomSearcherReusesThePreparedCollection() {
    val file = configureSharedService()
    val target = file.declarationsIncludingNested().klass("Service")
    val options = searchOptions()
    val prepared = runBlocking { collectMetroUsages(target, options) }
    val collected = mutableListOf<Usage>()

    val search = CompletableFuture.runAsync {
      ProgressManager.getInstance()
        .runProcess(
          {
            MetroUsageSearcher()
              .processElementUsages(
                target,
                Processor { usage ->
                  collected += usage
                  true
                },
                options,
              )
          },
          EmptyProgressIndicator(),
        )
    }
    PlatformTestUtil.waitForFuture(search, 30_000)
    search.join()

    assertEquals(2, prepared.size)
    assertEquals(prepared.size, collected.size)
    for (index in prepared.indices) {
      assertSame(prepared[index], collected[index])
    }

    val nextSearch = runBlocking { collectMetroUsages(target, searchOptions()) }
    assertNotSame(prepared, nextSearch)
    assertEquals(foundUsages(prepared), foundUsages(nextSearch))
  }

  fun testSearchCollectionTracksItsTargetAndScope() {
    val file = configureSharedService()
    val declarations = file.declarationsIncludingNested()
    val target = declarations.klass("Service")
    val options = searchOptions()
    val initial = runBlocking { collectMetroUsages(target, options) }
    assertEquals(2, initial.size)

    val accessor = declarations.property("firstService")
    val provider = runBlocking { collectMetroUsages(accessor, options) }
    assertEquals(listOf(FoundUsage("Service", "Provided by")), foundUsages(provider))

    options.searchScope = LocalSearchScope(declarations.klass("FirstGraph"))
    val scoped = runBlocking { collectMetroUsages(target, options) }
    assertEquals(listOf(FoundUsage("FirstGraph.firstService", "Injected at")), foundUsages(scoped))
  }

  fun testSearchCollectionTracksThePinnedGraph() {
    val file = configureSharedService()
    val target = file.declarationsIncludingNested().klass("Service")
    val options = searchOptions()
    assertEquals(2, runBlocking { collectMetroUsages(target, options) }.size)

    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "FirstGraph" }
    project.service<GraphContextPinService>().pin(index.contextsFor(graph).single().path)

    val pinned = runBlocking { collectMetroUsages(target, options) }
    assertEquals(listOf(FoundUsage("FirstGraph.firstService", "Injected at")), foundUsages(pinned))

    project.service<GraphContextPinService>().clear()
    assertEquals(2, runBlocking { collectMetroUsages(target, options) }.size)
  }

  fun testSearchCollectionTracksIndexRefreshes() {
    val file = configureSharedService()
    val target = file.declarationsIncludingNested().klass("Service")
    val options = searchOptions()
    val service = project.service<MetroResolutionService>()
    val initialIndex = service.awaitIndex(file)
    val initial = runBlocking { collectMetroUsages(target, options) }

    service.refreshGraphData()
    val updatedIndex = service.awaitIndex(file)
    assertNotSame(initialIndex.generationToken, updatedIndex.generationToken)

    val updated = runBlocking { collectMetroUsages(target, options) }
    assertNotSame(initial, updated)
    assertEquals(foundUsages(initial), foundUsages(updated))
  }

  fun testUnrelatedDeclarationsAndExcludedScopesProduceNoMetroUsages() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        @Inject class Consumer(val service: Service)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
          @Provides fun provideService(): Service = object : Service {}
        }

        fun unrelated() = Unit
        """
      )
    val declarations = file.declarationsIncludingNested()
    val provider = declarations.function("provideService")

    assertTrue(metroUsages(declarations.function("unrelated")).isEmpty())
    assertTrue(metroUsages(provider, LocalSearchScope(provider)).isEmpty())
  }

  fun testDisabledMetroModuleProducesNoMetroUsages() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service
        @DependencyGraph interface AppGraph {
          val service: Service
        }
        """
      )
    val service = file.declarationsIncludingNested().klass("Service")
    project.clearMetroOptions()

    assertTrue(metroUsages(service).isEmpty())
  }

  fun testDumbModeProducesNoMetroUsages() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service
        @DependencyGraph interface AppGraph {
          val service: Service
        }
        """
      )
    val service = file.declarationsIncludingNested().klass("Service")

    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      assertTrue(metroUsages(service).isEmpty())
    }
  }

  fun testInvalidTargetProducesNoMetroUsages() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        @DependencyGraph interface AppGraph {
          val service: Service
          @Provides fun provideService(): Service = object : Service {}
        }
        """
      )
    val provider = file.declarationsIncludingNested().function("provideService")
    WriteCommandAction.runWriteCommandAction(project) { provider.delete() }

    assertFalse(provider.isValid)
    assertTrue(metroUsages(provider).isEmpty())
  }

  private fun metroUsages(
    target: PsiElement,
    scope: SearchScope = GlobalSearchScope.projectScope(project),
  ): List<FoundUsage> {
    val usages = mutableListOf<Usage>()
    val options = FindUsagesOptions(project).apply { searchScope = scope }
    usages += runBlocking { collectMetroUsages(target, options) }
    return foundUsages(usages)
  }

  private fun foundUsages(usages: List<Usage>): List<FoundUsage> {
    return usages.map { usage ->
      val element = (usage as PsiElementUsage).element
      val declaration =
        when (element) {
          is KtDeclaration -> element
          else -> PsiTreeUtil.getParentOfType(element, KtDeclaration::class.java, false)
        }
      checkNotNull(declaration)
      FoundUsage(declaration.testName(), (usage as UsageWithType).usageType.toString())
    }
  }

  private fun configureSharedService(): KtFile {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service

        @DependencyGraph interface FirstGraph {
          val firstService: Service
        }

        @DependencyGraph interface SecondGraph {
          val secondService: Service
        }
        """
      )
    project.service<MetroResolutionService>().awaitIndex(file)
    return file
  }

  private fun searchOptions(): FindUsagesOptions {
    return FindUsagesOptions(project).apply {
      searchScope = GlobalSearchScope.projectScope(project)
      fastTrack = SearchRequestCollector(SearchSession())
    }
  }
}

private data class FoundUsage(val declaration: String, val relationship: String)

private fun KtDeclaration.testName(): String {
  val name = (this as? KtNamedDeclaration)?.name ?: text
  val owner = ownerName()
  return if (owner == null) name else "$owner.$name"
}

private fun KtDeclaration.ownerName(): String? {
  return PsiTreeUtil.getParentOfType(parent, KtNamedDeclaration::class.java, false)?.name
}
