// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.GraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.graph.runGraphValidation
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.KaBinding
import kotlinx.coroutines.CancellationException

/** Seals graphs through [MetroGraphValidationService] and asserts the reported diagnostics. */
class MetroGraphValidationTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    // Results are retained across index invalidation by design, so they survive across tests
    // sharing this project. Start each test clean.
    project.service<MetroGraphValidationService>().clearResults()
  }

  private fun validate(
    source: String,
    graphName: String = "AppGraph",
  ): GraphValidationResult.Completed {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single { it.name == graphName }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
  }

  fun testUnexpectedFailureReturnsInternalError() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val index = project.service<MetroResolutionService>().index(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val failure = IllegalStateException("broken model")
    var reported: Throwable? = null

    val result =
      runGraphValidation(
        context = context,
        graphName = "test.AppGraph",
        onInternalError = { reported = it },
      ) {
        throw failure
      }

    assertTrue(result is GraphValidationResult.InternalError)
    result as GraphValidationResult.InternalError
    assertSame(context, result.context)
    assertSame(failure, result.cause)
    assertSame(failure, reported)
  }

  fun testCancellationEscapesInternalErrorBoundary() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val index = project.service<MetroResolutionService>().index(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val cancellation = CancellationException("cancelled")
    var reported: Throwable? = null

    try {
      runGraphValidation(
        context = context,
        graphName = "test.AppGraph",
        onInternalError = { reported = it },
      ) {
        throw cancellation
      }
      fail("Expected cancellation")
    } catch (e: CancellationException) {
      assertSame(cancellation, e)
    }
    assertNull(reported)
  }

  fun testCleanGraphHasNoDiagnostics() {
    val result =
      validate(
        """

        interface Service
        interface Analytics

        @Inject class ServiceImpl : Service

        interface ServiceBindings {
          @Binds fun bindService(impl: ServiceImpl): Service
        }

        @Inject @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics

        @Inject class Consumer(val service: Service, val analytics: Set<Analytics>)

        @DependencyGraph(AppScope::class, bindingContainers = [ServiceBindings::class])
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val topology = result.topology!!
    assertTrue(topology.sortedKeys.any { it.renderedType == "test.Consumer" })
    assertTrue(topology.deferredTypes.isEmpty())
    // The aggregate node participates in the sealed bindings
    assertTrue(
      result.bindings.any { key, _ -> key.renderedType.startsWith("kotlin.collections.Set") }
    )
  }

  fun testIncludedDependencyInstanceCanSatisfyContainerProvider() {
    val result =
      validate(
        """
        interface Bar {
          val a: Int
        }

        @BindingContainer
        object Foo {
          @Provides fun value(bar: Bar): String = bar.a.toString()
        }

        @DependencyGraph(bindingContainers = [Foo::class])
        interface AppGraph {
          val value: String

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bar: Bar): AppGraph
          }
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, binding ->
        key.renderedType == "test.Bar" && binding is KaBinding.BoundInstance && binding.isGraphInput
      }
    )
  }

  fun testMissingBindingIsReportedWithRequestTrace() {
    val result =
      validate(
        """

        interface MissingThing

        @DependencyGraph
        interface AppGraph {
          val missing: MissingThing
        }
        """
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    val rendered = diagnostic.render()
    assertTrue(rendered, "No binding found for MissingThing" in rendered)
    assertTrue(rendered, "MissingThing is requested at test.AppGraph.missing" in rendered)
  }

  fun testOptionalAbsenceIsNotAnError() {
    val result =
      validate(
        """

        interface HttpClient

        @DependencyGraph
        interface AppGraph {
          @OptionalBinding val httpClient: HttpClient? get() = null
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testHardCycleAbortsWithDependencyCycle() {
    val result =
      validate(
        """

        @Inject class A(val b: B)
        @Inject class B(val a: A)

        @DependencyGraph
        interface AppGraph {
          val a: A
        }
        """
      )
    assertEquals(
      listOf(MetroDiagnosticId.DEPENDENCY_CYCLE),
      result.diagnostics.map { it.id },
    )
    assertNull(result.topology)
  }

  fun testProviderBreaksCycle() {
    val result =
      validate(
        """

        @Inject class A(val b: Provider<B>)
        @Inject class B(val a: A)

        @DependencyGraph
        interface AppGraph {
          val a: A
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.topology!!.deferredTypes.isNotEmpty())
  }

  fun testDuplicateBindingsAreReported() {
    val result =
      validate(
        """

        interface UrlProviders {
          @Provides fun provideUrl(): String = "a"
          @Provides fun provideOtherUrl(): String = "b"
        }

        @DependencyGraph(bindingContainers = [UrlProviders::class])
        interface AppGraph {
          val url: String
        }
        """
      )
    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_BINDING), result.diagnostics.map { it.id })
    val diagnostic = result.diagnostics.single()
    assertTrue(diagnostic.render(), "Multiple bindings found for" in diagnostic.render())
    // The duplicate sources ride along for navigation
    assertEquals(2, diagnostic.related.size)
  }

  fun testDuplicateMapKeysAreReported() {
    val result =
      validate(
        """

        interface Service

        interface HandlerProviders {
          @Provides @IntoMap @StringKey("a") fun handlerA(): Service = object : Service {}
          @Provides @IntoMap @StringKey("a") fun handlerB(): Service = object : Service {}
        }

        @DependencyGraph(bindingContainers = [HandlerProviders::class])
        interface AppGraph {
          val handlers: Map<String, Service>
        }
        """
      )
    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_MAP_KEYS), result.diagnostics.map { it.id })
    val diagnostic = result.diagnostics.single()
    assertTrue(diagnostic.render(), "same map key" in diagnostic.render())
    assertEquals(2, diagnostic.related.size)
  }

  fun testEmptyMultibindingIsReported() {
    val result =
      validate(
        """

        interface Service

        interface Declarations {
          @Multibinds fun services(): Set<Service>
        }

        @DependencyGraph(bindingContainers = [Declarations::class])
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    assertEquals(listOf(MetroDiagnosticId.EMPTY_MULTIBINDING), result.diagnostics.map { it.id })
  }

  fun testEmptyMultibindingAllowedWhenDeclared() {
    val result =
      validate(
        """

        interface Service

        interface Declarations {
          @Multibinds(allowEmpty = true) fun services(): Set<Service>
        }

        @DependencyGraph(bindingContainers = [Declarations::class])
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testAnyMultibindsDeclarationCanAllowEmpty() {
    val result =
      validate(
        """

        interface Service

        @BindingContainer
        interface StrictDeclarations {
          @Multibinds fun services(): Set<Service>
        }

        @BindingContainer
        interface EmptyDeclarations {
          @Multibinds(allowEmpty = true) fun services(): Set<Service>
        }

        @DependencyGraph(
          bindingContainers = [StrictDeclarations::class, EmptyDeclarations::class]
        )
        interface AppGraph {
          val services: Set<Service>
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testOptionalBindingTraversesPresentDependencyAndAllowsAbsentDependency() {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      """
      package dagger

      annotation class BindsOptionalOf
      """
        .trimIndent(),
    )
    // The light test fixture's mock JDK lacks java.util.Optional.
    myFixture.addFileToProject(
      "java/util/Optional.kt",
      """
      package java.util

      class Optional<T>
      """
        .trimIndent(),
    )
    val result =
      validate(
        """
        import dagger.BindsOptionalOf
        import java.util.Optional

        interface PresentService
        interface MissingService

        @Inject class RealPresentService : PresentService

        @BindingContainer
        interface OptionalBindings {
          @Binds fun bindPresent(impl: RealPresentService): PresentService
          @BindsOptionalOf fun optionalPresent(): PresentService
          @BindsOptionalOf fun optionalMissing(): MissingService
        }

        @DependencyGraph(bindingContainers = [OptionalBindings::class])
        interface AppGraph {
          val present: Optional<PresentService>
          val missing: Optional<MissingService>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "test.PresentService" })
  }

  fun testScopeFilteredCandidateReportsIncompatibleScope() {
    val result =
      validate(
        """

        interface Api

        interface ApiProviders {
          @Provides @SingleIn(AppScope::class) fun provideApi(): Api = object : Api {}
        }

        @DependencyGraph(bindingContainers = [ApiProviders::class])
        interface AppGraph {
          val api: Api
        }
        """
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INCOMPATIBLY_SCOPED_BINDINGS, diagnostic.id)
    assertTrue(diagnostic.render(), "may not reference scoped bindings" in diagnostic.render())
  }

  fun testAssistedClassInjectionIsHinted() {
    val result =
      validate(
        """

        @AssistedInject class Widget(@Assisted val id: String)

        @AssistedFactory
        interface WidgetFactory {
          fun create(id: String): Widget
        }

        @DependencyGraph
        interface AppGraph {
          val widget: Widget
        }
        """
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "assisted-injected" in diagnostic.render())
  }

  fun testGraphExtensionSealsAgainstParentChain() {
    val result =
      validate(
        """

        interface Api

        interface ApiProviders {
          @Provides fun provideApi(): Api = object : Api {}
        }

        @Inject class ChildThing(val api: Api)

        @GraphExtension
        interface ChildGraph {
          val childThing: ChildThing
        }

        @DependencyGraph(bindingContainers = [ApiProviders::class])
        interface AppGraph {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "test.ChildThing" })
  }

  fun testMultiParentExtensionSealsEachParentPathIndependently() {
    val file =
      myFixture.configureMetroFile(
        """
        interface LeftOnly
        interface RightOnly

        @Inject class ChildThing(val left: LeftOnly, val right: RightOnly)

        @GraphExtension
        interface ChildGraph {
          val childThing: ChildThing
        }

        @DependencyGraph
        interface LeftParent {
          val child: ChildGraph

          @Provides fun provideLeft(): LeftOnly = object : LeftOnly {}
        }

        @DependencyGraph
        interface RightParent {
          val child: ChildGraph

          @Provides fun provideRight(): RightOnly = object : RightOnly {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val child = index.graphs.single { it.name == "ChildGraph" }
    val contextsByParent = index.contextsFor(child).associateBy { it.chain[1].name }
    val leftContext = contextsByParent.getValue("LeftParent")
    val rightContext = contextsByParent.getValue("RightParent")
    val validationService = project.service<MetroGraphValidationService>()

    val leftResult = validationService.validate(file, leftContext).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), leftResult.diagnostics.map { it.id })
    val leftDiagnostic = leftResult.diagnostics.single().render()
    assertTrue(leftDiagnostic, "RightOnly" in leftDiagnostic)
    assertNotNull(validationService.cachedResult(file, leftContext))
    assertNull(validationService.cachedResult(file, rightContext))

    val rightResult = validationService.validate(file, rightContext).requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), rightResult.diagnostics.map { it.id })
    val rightDiagnostic = rightResult.diagnostics.single().render()
    assertTrue(rightDiagnostic, "LeftOnly" in rightDiagnostic)

    validationService.clearResults()
    val leftParent = index.graphs.single { it.name == "LeftParent" }
    val traversal = validationService.validateWithExtensions(file, leftParent)
    assertEquals(listOf("ChildGraph", "LeftParent"), traversal.map { it.graph.name })
    assertEquals("LeftParent", traversal.first().context.chain[1].name)
    assertNull(validationService.cachedResult(file, rightContext))
  }

  fun testGraphInstanceIsInjectable() {
    val result =
      validate(
        """

        @Inject class NeedsGraph(val graph: AppGraph)

        @DependencyGraph
        interface AppGraph {
          val needsGraph: NeedsGraph
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testGraphLocalProvidersDoNotConflictAcrossGraphs() {
    val source =
      """

      @Inject class SharedConsumer(val url: String)

      @DependencyGraph
      interface AppGraph {
        val consumer: SharedConsumer

        @Provides fun provideUrl(): String = "app"
      }

      @DependencyGraph
      interface OtherGraph {
        val consumer: SharedConsumer

        @Provides fun provideUrl(): String = "other"
      }
      """
    val appResult = validate(source, graphName = "AppGraph")
    assertTrue(appResult.diagnostics.joinToString { it.render() }, appResult.diagnostics.isEmpty())

    val otherResult = validate(source, graphName = "OtherGraph")
    assertTrue(
      otherResult.diagnostics.joinToString { it.render() },
      otherResult.diagnostics.isEmpty(),
    )
  }

  fun testValidatingAParentAlsoValidatesItsExtensions() {
    val file =
      myFixture.configureMetroFile(
        """
        interface MissingThing

        @GraphExtension
        interface ChildGraph {
          val missing: MissingThing
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val appGraph = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, appGraph)

    // Extensions seal first, the requested graph last
    assertEquals(listOf("ChildGraph", "AppGraph"), results.map { it.graph.name })
    val childResult = results.first().requireCompleted()
    assertEquals(
      listOf(MetroDiagnosticId.MISSING_BINDING),
      childResult.diagnostics.map { it.id },
    )
    assertTrue(results.last().requireCompleted().diagnostics.isEmpty())
  }

  fun testReplacedContributionKeepsItsOwnInjectableType() {
    val result =
      validate(
        """
        interface Repo

        @Inject @ContributesBinding(AppScope::class)
        class RealRepo : Repo

        @Inject
        @ContributesBinding(AppScope::class, replaces = [RealRepo::class])
        class StubRepo(val real: RealRepo) : Repo

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val repo: Repo
        }
        """
      )
    // Replaces drops RealRepo's contributed Repo binding, but RealRepo itself stays injectable
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "test.RealRepo" })
  }

  fun testCompanionObjectProvidesBelongToTheirContainer() {
    val result =
      validate(
        """
        interface Api

        interface ApiProviders {
          companion object {
            @Provides fun provideApi(): Api = object : Api {}
          }
        }

        @DependencyGraph(bindingContainers = [ApiProviders::class])
        interface AppGraph {
          val api: Api
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testGraphSupertypeMembersMergeIntoTheGraph() {
    val result =
      validate(
        """
        interface Json

        interface BaseGraph {
          val baseJson: Json

          @Provides fun provideJson(): Json = object : Json {}
        }

        @DependencyGraph
        interface AppGraph : BaseGraph {
          val json: Json
        }
        """
      )
    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    // Both the graph's own accessor and the supertype's accessor resolve to the supertype provider
    assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "test.Json" })
  }

  fun testSameFqnGraphsInDifferentFilesDoNotShareResults() {
    val source =
      """
      package test

      import dev.zacsweers.metro.*

      @DependencyGraph interface AppGraph
      """
        .trimIndent()
    val fileA = myFixture.addFileToProject("a/Graphs.kt", source)
    myFixture.addFileToProject("b/Graphs.kt", source)

    val index = project.service<MetroResolutionService>().index(fileA)
    val graphs = index.graphs.filter { it.classId?.asFqNameString() == "test.AppGraph" }
    assertEquals(2, graphs.size)
    val (graphA, graphB) = graphs

    val validationService = project.service<MetroGraphValidationService>()
    val contextA = index.contextsFor(graphA).single()
    val contextB = index.contextsFor(graphB).single()
    validationService.validate(fileA, contextA)

    // Same ClassId, different declarations: only the validated one has a result
    assertNotNull(validationService.cachedResult(fileA, contextA))
    assertNull(validationService.cachedResult(fileA, contextB))
  }

  fun testBinaryGraphSupertypeMembersMerge() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibBaseGraph

          @DependencyGraph
          interface AppGraph : LibBaseGraph
          """
        )
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "libtest.LibJson" })
    }
  }

  fun testResultsAreCachedPerIndex() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val validationService = project.service<MetroGraphValidationService>()
    val first = validationService.validate(file, context)
    val second = validationService.validate(file, context)
    assertSame(first, second)
  }

  fun testResultsSurviveIndexInvalidationAsStale() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val validationService = project.service<MetroGraphValidationService>()
    val result = validationService.validate(file, context)
    assertFalse(validationService.cachedResult(file, context)!!.stale)

    // Any PSI change invalidates the index; the result must stay visible, flagged stale
    myFixture.openFileInEditor(file.virtualFile)
    myFixture.type(" ")
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val cached = validationService.cachedResult(file, context)!!
    assertSame(result, cached.result)
    assertTrue(cached.stale)
  }
}
