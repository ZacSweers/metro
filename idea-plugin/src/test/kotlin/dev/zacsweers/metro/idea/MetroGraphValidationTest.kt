// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.GraphValidationProgress
import dev.zacsweers.metro.idea.graph.IncompleteGraphAnalysis
import dev.zacsweers.metro.idea.graph.KaBindingGraph
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.graph.runGraphValidation
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.DeclarationResolutionScope
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

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
  ): KaGraphValidationResult.Completed {
    return validateResult(source, graphName).requireCompleted()
  }

  private fun validateResult(
    source: String,
    graphName: String = "AppGraph",
  ): KaGraphValidationResult {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single { it.name == graphName }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
  }

  private fun validateWithoutLibraryResolution(
    source: String,
    graphName: String = "AppGraph",
  ): KaGraphValidationResult.Completed {
    val settings = MetroSettings.getInstance(project).state
    val previous = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    return try {
      validate(source, graphName)
    } finally {
      settings.resolveFromLibraries = previous
    }
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

    assertTrue(result is KaGraphValidationResult.InternalError)
    result as KaGraphValidationResult.InternalError
    assertSame(context, result.context)
    assertSame(failure, result.cause)
    assertSame(failure, reported)
  }

  fun testExpectedAnalysisLimitReturnsIncompleteWithoutLoggingAnInternalError() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val index = project.service<MetroResolutionService>().index(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val reason = "source assisted-factory analysis reached its type-depth limit"
    var reported: Throwable? = null

    val result =
      runGraphValidation(
        context = context,
        graphName = "test.AppGraph",
        onInternalError = { reported = it },
      ) {
        throw IncompleteGraphAnalysis(reason)
      }

    assertTrue(result is KaGraphValidationResult.Incomplete)
    result as KaGraphValidationResult.Incomplete
    assertSame(context, result.context)
    assertEquals(reason, result.reason)
    assertNull(reported)
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

  fun testPlatformCancellationEscapesInternalErrorBoundary() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val index = project.service<MetroResolutionService>().index(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val cancellation = ProcessCanceledException()
    var reported: Throwable? = null

    try {
      runGraphValidation(
        context = context,
        graphName = "test.AppGraph",
        onInternalError = { reported = it },
      ) {
        throw cancellation
      }
      fail("Expected platform cancellation")
    } catch (e: ProcessCanceledException) {
      assertSame(cancellation, e)
    }
    assertNull(reported)
  }

  fun testPlatformCancellationRetriesGraphValidation() {
    val file = myFixture.configureMetroFile("@DependencyGraph interface AppGraph")
    val index = project.service<MetroResolutionService>().index(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val expected = project.service<MetroGraphValidationService>().validate(file, context)
    var attempts = 0
    var reported: Throwable? = null

    val result = runBlocking {
      retryCancelledIndexBuild {
        runGraphValidation(
          context = context,
          graphName = "test.AppGraph",
          onInternalError = { reported = it },
        ) {
          attempts++
          if (attempts == 1) throw ProcessCanceledException()
          expected.requireCompleted()
        }
      }
    }

    assertSame(expected, result)
    assertEquals(2, attempts)
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
    // The multibinding node participates in the sealed bindings
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

  fun testChildFactoryInputSupersedesTheSameParentInput() {
    val file =
      myFixture.configureMetroFile(
        """
        interface ExternalDependency

        @GraphExtension
        interface ChildGraph {
          val dependency: ExternalDependency

          @GraphExtension.Factory
          interface Factory {
            fun create(@Includes childDependency: ExternalDependency): ChildGraph
          }
        }

        @DependencyGraph
        interface AppGraph {
          val childFactory: ChildGraph.Factory

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes parentDependency: ExternalDependency): AppGraph
          }
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)
    val childResult = results.first().requireCompleted()

    assertTrue(
      childResult.diagnostics.joinToString { it.render() },
      childResult.diagnostics.isEmpty(),
    )
    assertTrue(
      childResult.bindings.any { key, binding ->
        key.renderedType == "test.ExternalDependency" &&
          binding is KaBinding.BoundInstance &&
          (binding.pointer.element as? KtParameter)?.name == "childDependency"
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

  fun testRequiredAccessorWinsWhenOptionalAccessorForSameKeyComesFirst() {
    val result =
      validate(
        """

        interface HttpClient

        @DependencyGraph
        interface AppGraph {
          @OptionalBinding fun optionalHttpClient(): HttpClient = error("unused")
          val requiredHttpClient: HttpClient
        }
        """
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "requiredHttpClient" in diagnostic.render())
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

  fun testRepeatedOptionalBindingDeclarationsAreNotDuplicates() {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      "package dagger\n\nannotation class BindsOptionalOf",
    )
    myFixture.addFileToProject("java/util/Optional.kt", "package java.util\n\nclass Optional<T>")
    val result =
      validate(
        """
        import dagger.BindsOptionalOf
        import java.util.Optional

        interface Service

        @BindingContainer
        interface FirstBindings {
          @BindsOptionalOf fun optionalService(): Service
        }

        @BindingContainer
        interface SecondBindings {
          @BindsOptionalOf fun optionalService(): Service
        }

        @DependencyGraph(bindingContainers = [FirstBindings::class, SecondBindings::class])
        interface AppGraph {
          val service: Optional<Service>
        }
        """
      )
    assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
  }

  fun testChildDeclaredParentScopedProvidesReportsIncompatibleScope() {
    // The scope names an ancestor but the declaration is the child's own, so it stays local and
    // must fail scope validation like the compiler's node scopes, which exclude parent scopes.
    val result =
      validate(
        """
        class Value

        @GraphExtension
        interface ChildGraph {
          val value: Value

          @Provides @SingleIn(AppScope::class) fun provideValue(): Value = Value()
        }

        @SingleIn(AppScope::class)
        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val childGraph: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INCOMPATIBLY_SCOPED_BINDINGS, diagnostic.id)
    assertTrue(diagnostic.render(), "ChildGraph (unscoped)" in diagnostic.render())
  }

  fun testChildIncludedContainerParentScopedProvidesStaysLocal() {
    val result =
      validate(
        """
        class Value

        @BindingContainer
        interface ChildBindings {
          companion object {
            @Provides @SingleIn(AppScope::class) fun provideValue(): Value = Value()
          }
        }

        @GraphExtension(bindingContainers = [ChildBindings::class])
        interface ChildGraph {
          val value: Value
        }

        @SingleIn(AppScope::class)
        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val childGraph: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INCOMPATIBLY_SCOPED_BINDINGS, diagnostic.id)
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

  fun testChildInheritedGenericProviderSupersedesTheSameParentDeclaration() {
    val result =
      validate(
        """
        interface GenericProviders<T> {
          @Provides fun provideText(value: T): String = value.toString()
        }

        @GraphExtension
        interface ChildGraph : GenericProviders<Int> {
          val text: String
        }

        @DependencyGraph
        interface AppGraph : GenericProviders<Int> {
          val child: ChildGraph

          @Provides fun provideInt(): Int = 1
        }
        """,
        graphName = "ChildGraph",
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val childBinding =
      result.bindings.asMap().values.single {
        it is KaBinding.Provided && it.typeKey.renderedType == "kotlin.String"
      }
    assertEquals(result.graph.declarationId, childBinding.ownerGraphId)
  }

  fun testChildInheritedGenericAliasSupersedesTheSameParentDeclaration() {
    val result =
      validate(
        """
        interface Service
        @Inject class RealService : Service

        interface GenericBindings<T : Service> {
          @Binds fun bindService(value: T): Service
        }

        @GraphExtension
        interface ChildGraph : GenericBindings<RealService> {
          val service: Service
        }

        @DependencyGraph
        interface AppGraph : GenericBindings<RealService> {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val childBinding =
      result.bindings.asMap().values.single {
        it is KaBinding.Alias && it.typeKey.renderedType == "test.Service"
      }
    assertEquals(result.graph.declarationId, childBinding.ownerGraphId)
  }

  fun testDifferentInheritedGenericProvidersInOneGraphRemainDuplicates() {
    val result =
      validate(
        """
        interface FirstProviders<T> {
          @Provides fun first(value: T): String = value.toString()
        }

        interface SecondProviders<T> {
          @Provides fun second(value: T): String = value.toString()
        }

        @DependencyGraph
        interface AppGraph : FirstProviders<Int>, SecondProviders<Int> {
          val text: String

          @Provides fun provideInt(): Int = 1
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_BINDING), result.diagnostics.map { it.id })
    assertEquals(2, result.diagnostics.single().related.size)
  }

  fun testSetContributionsAreAdditive() {
    val result =
      validate(
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

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertEquals(
      setOf("FirstService", "SecondService"),
      result.bindings.asMap().values.mapNotNullTo(mutableSetOf()) { binding ->
        binding.implementationName.takeIf { binding.multibindingId != null }
      },
    )
  }

  fun testKeyedCustomSetContributionsRemainMapEntries() {
    project.setMetroOptions("custom-contributes-into-set" to "test/PrioritizedMultibinding")

    val result =
      validate(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMultibinding(
          val scope: KClass<*>,
          val priority: Int = Int.MIN_VALUE,
        )

        interface Service

        @Inject @StringKey("first")
        @PrioritizedMultibinding(AppScope::class, priority = 100)
        class FirstMapService : Service

        @Inject @StringKey("second")
        @PrioritizedMultibinding(AppScope::class, priority = 100)
        class SecondMapService : Service

        @Inject @PrioritizedMultibinding(AppScope::class)
        class SetService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val mapped: Map<String, Service>
          val collected: Set<Service>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testMixedOrdinaryAndSetAnnotationsStayAdditive() {
    project.setMetroOptions("custom-contributes-binding" to "test/MixedContribution")

    val result =
      validate(
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
        @MixedContribution(AppScope::class)
        @MixedContribution(AppScope::class, multibinding = true)
        class SharedService : Service

        @Inject @MixedContribution(AppScope::class, multibinding = true)
        class OtherSetService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val services: Set<Service>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
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
    assertTrue(diagnostic.stack.isNotEmpty())
  }

  fun testMapPriorityPreventsDuplicateMapKeyDiagnostic() {
    project.setMetroOptions("custom-into-map" to "test/PrioritizedMapContribution")

    val result =
      validate(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMapContribution(val scope: KClass<*>, val priority: Int)

        interface Service

        @Inject @StringKey("same")
        @PrioritizedMapContribution(AppScope::class, priority = 10)
        class LowerService : Service

        @Inject @StringKey("same")
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

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertEquals(
      setOf("HigherService", "OtherService"),
      result.bindings.asMap().values.mapNotNullTo(mutableSetOf()) { binding ->
        binding.implementationName.takeIf { binding.mapKeyValue != null }
      },
    )
  }

  fun testEqualMapContributionPrioritiesStillReportDuplicateMapKeys() {
    project.setMetroOptions("custom-into-map" to "test/PrioritizedMapContribution")

    val result =
      validate(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedMapContribution(val scope: KClass<*>, val priority: Int)

        interface Service

        @Inject @StringKey("same")
        @PrioritizedMapContribution(AppScope::class, priority = 100)
        class FirstService : Service

        @Inject @StringKey("same")
        @PrioritizedMapContribution(AppScope::class, priority = 100)
        class SecondService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val services: Map<String, Service>
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_MAP_KEYS), result.diagnostics.map { it.id })
    assertEquals(2, result.diagnostics.single().related.size)
  }

  fun testParentScopedMapContributionKeepsItsMapKeyWhenDelegated() {
    val result =
      validate(
        """
        @GraphExtension
        interface ChildGraph {
          val values: Map<String, String>

          @Provides @IntoMap @StringKey("same")
          fun childValue(): String = "child"
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph

          @Provides @IntoMap @StringKey("same") @SingleIn(AppScope::class)
          fun parentValue(): String = "parent"
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_MAP_KEYS), result.diagnostics.map { it.id })
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

  fun testAssistedClassGraphRequestIsRejected() {
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
    assertEquals(MetroDiagnosticId.INVALID_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "uses assisted injection" in diagnostic.render())
    assertTrue(
      diagnostic.render(),
      "inject a corresponding @AssistedFactory" in diagnostic.render(),
    )
  }

  fun testAssistedClassDependencyIsRejected() {
    val result =
      validate(
        """

        @AssistedInject class Widget(@Assisted val id: String)

        @AssistedFactory
        interface WidgetFactory {
          fun create(id: String): Widget
        }

        @Inject class Screen(val widget: Widget)

        @DependencyGraph
        interface AppGraph {
          val screen: Screen
        }
        """
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INVALID_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "uses assisted injection" in diagnostic.render())
    assertTrue(diagnostic.render(), "Screen" in diagnostic.render())
  }

  fun testLazyAssistedFactoryReportsEachGraphAccessor() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @DependencyGraph
        interface AppGraph {
          val propertyFactory: Lazy<Widget.Factory>

          fun functionFactory(): Lazy<Widget.Factory>
        }
        """
      )

    assertEquals(
      listOf(MetroDiagnosticId.INVALID_BINDING, MetroDiagnosticId.INVALID_BINDING),
      result.diagnostics.map { it.id },
    )
    assertTrue(
      result.diagnostics.all {
        "does not support injecting Lazy<Factory>" in it.render()
      }
    )
    assertEquals(2, result.diagnostics.map { it.stack.first().pointer }.distinct().size)
  }

  fun testLazyAssistedFactoryConstructorDependencyIsRejected() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject class Consumer(val factory: Lazy<Widget.Factory>)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INVALID_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "Widget.Factory" in diagnostic.render())
  }

  fun testQualifiedLazyAssistedFactoryDependencyIsRejectedBeforeMissingBinding() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject class Consumer(@Chosen val factory: Lazy<Widget.Factory>)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )

    assertEquals(MetroDiagnosticId.INVALID_BINDING, result.diagnostics.first().id)
  }

  fun testEachSameKeyLazyFactoryConstructorParameterIsRejected() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject
        class Consumer(
          val first: Lazy<Widget.Factory>,
          val second: Lazy<Widget.Factory>,
        )

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )

    val diagnostics = result.diagnostics.filter { it.id == MetroDiagnosticId.INVALID_BINDING }
    assertEquals(2, diagnostics.size)
    assertEquals(
      setOf("first", "second"),
      diagnostics.mapTo(mutableSetOf()) {
        (it.stack.first().pointer?.element as? KtParameter)?.name
      },
    )
  }

  fun testLazyFactorySourceLookupVisitsEachParameterOnce() {
    module.addKotlinStdlibLibrary()
    val consumerCount = 32
    val source = buildString {
      appendLine(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }
        """
          .trimIndent()
      )
      repeat(consumerCount) { number ->
        appendLine("@Inject class Consumer$number(val factory: Lazy<Widget.Factory>)")
      }
      appendLine("@DependencyGraph interface AppGraph {")
      repeat(consumerCount) { number ->
        appendLine("  val consumer$number: Consumer$number")
      }
      appendLine("}")
    }
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val queryContext = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
    var parameterScopeChecks = 0
    val countedContext =
      GraphQueryContext(
        queryContext.graphContext,
        queryContext.graphModule,
        DeclarationResolutionScope { element ->
          if (element is KtParameter && element.name == "factory") parameterScopeChecks++
          queryContext.resolutionScope.contains(element)
        },
        queryContext.containers,
      )

    val result = KaBindingGraph(index, countedContext, file.metroIdeState().options).seal()
    val diagnostics = result.diagnostics.filter { it.id == MetroDiagnosticId.INVALID_BINDING }
    val parameters = diagnostics.map { it.stack.first().pointer?.element as? KtParameter }

    assertEquals(consumerCount, diagnostics.size)
    assertTrue(parameters.all { it?.name == "factory" })
    assertEquals(consumerCount, parameters.distinct().size)
    assertTrue(
      "Expected linear source lookup, got $parameterScopeChecks parameter scope checks",
      parameterScopeChecks <= consumerCount * 2,
    )
  }

  fun testLazyAssistedFactoryProviderDependencyIsRejected() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @DependencyGraph
        interface AppGraph {
          val text: String

          @Provides fun provideText(factory: Lazy<Widget.Factory>): String = "ready"
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testEachSameKeyLazyFactoryProviderParameterKeepsItsSource() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @DependencyGraph
        interface AppGraph {
          val text: String

          @Provides
          fun provideText(first: Lazy<Widget.Factory>, second: Lazy<Widget.Factory>): String = "ready"
        }
        """
      )

    val diagnostics = result.diagnostics.filter { it.id == MetroDiagnosticId.INVALID_BINDING }
    assertEquals(2, diagnostics.size)
    assertEquals(
      setOf("first", "second"),
      diagnostics.mapTo(mutableSetOf()) {
        (it.stack.first().pointer?.element as? KtParameter)?.name
      },
    )
  }

  fun testLazyAssistedFactoryInjectedMemberIsRejected() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject
        class Consumer {
          @Inject lateinit var factory: Lazy<Widget.Factory>
        }

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testLazyAssistedMemberRequestedThroughAnInjectorIsReportedOnce() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject
        class Consumer {
          @Inject lateinit var factory: Lazy<Widget.Factory>
        }

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer

          fun inject(consumer: Consumer)
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testInjectorOnlyLazyAssistedMemberIsReportedOnce() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        class Target {
          @Inject lateinit var factory: Lazy<Widget.Factory>
        }

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target)
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
    assertEquals(
      "factory",
      (result.diagnostics.single().stack.first().pointer?.element as? KtProperty)?.name,
    )
  }

  fun testEachLazyFactoryInjectedMethodParameterKeepsItsSource() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        class Target {
          @Inject
          fun install(first: Lazy<Widget.Factory>, second: Lazy<Widget.Factory>) = Unit
        }

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target)
        }
        """
      )

    val diagnostics = result.diagnostics.filter { it.id == MetroDiagnosticId.INVALID_BINDING }
    assertEquals(2, diagnostics.size)
    assertEquals(
      setOf("first", "second"),
      diagnostics.mapTo(mutableSetOf()) {
        (it.stack.first().pointer?.element as? KtParameter)?.name
      },
    )
  }

  fun testInheritedGenericInjectedMethodLazyFactoryParametersKeepTheirSources() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @HasMemberInjections
        open class MemberBase<T> {
          @Inject
          fun install(first: Lazy<Widget.Factory>, second: Lazy<Widget.Factory>) = Unit
        }

        class Target : MemberBase<String>()

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target)
        }
        """
      )

    val diagnostics = result.diagnostics.filter { it.id == MetroDiagnosticId.INVALID_BINDING }
    assertEquals(2, diagnostics.size)
    assertEquals(
      setOf("first", "second"),
      diagnostics.mapTo(mutableSetOf()) {
        (it.stack.first().pointer?.element as? KtParameter)?.name
      },
    )
  }

  fun testLazyAssistedFactoryInsideItsOwnTargetIsRejected() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget<T>(@Assisted val id: String, val factory: Lazy<Factory<T>>) {
          @AssistedFactory
          interface Factory<T> {
            fun create(id: String): Widget<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Widget.Factory<Int>
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
    assertTrue(
      result.diagnostics.single().render(),
      "Lazy<Factory>" in result.diagnostics.single().render(),
    )
  }

  fun testQualifiedLazyAssistedFactoryIsRejectedBeforeMissingBinding() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @DependencyGraph
        interface AppGraph {
          @Chosen val factory: Lazy<Widget.Factory>
        }
        """
      )

    assertEquals(MetroDiagnosticId.INVALID_BINDING, result.diagnostics.first().id)
    assertTrue(
      result.diagnostics.first().render(),
      "Lazy<Factory>" in result.diagnostics.first().render(),
    )
  }

  fun testExplicitQualifiedFactoryProviderDoesNotMakeLazyInjectionValid() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @DependencyGraph
        interface AppGraph {
          @Chosen val factory: Lazy<Widget.Factory>

          @Provides @Chosen fun provideFactory(): Widget.Factory = error("unused")
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testExcludedAssistedFactoryStillRejectsQualifiedLazyInjection() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        interface PublicFactory {
          fun create(id: String): Widget
        }

        @AssistedInject
        class Widget(@Assisted val id: String)

        @AssistedFactory @ContributesBinding(AppScope::class)
        interface WidgetFactory : PublicFactory {
          override fun create(id: String): Widget
        }

        @DependencyGraph(AppScope::class, excludes = [WidgetFactory::class])
        interface AppGraph {
          @Chosen val factory: Lazy<WidgetFactory>

          @Provides @Chosen
          fun provideFactory(): WidgetFactory = error("unused")
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
    val diagnostic = result.diagnostics.single().render()
    assertTrue(diagnostic, "Lazy<WidgetFactory>" in diagnostic)
    assertTrue(diagnostic, "@AssistedFactory-annotated type" in diagnostic)
  }

  fun testBinaryLazyAssistedFactoryIsRejected() {
    module.addKotlinStdlibLibrary()
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibGenericAssistedExample

          @DependencyGraph
          interface AppGraph {
            val factory: Lazy<LibGenericAssistedExample.Factory<Int>>

            @Provides fun provideInt(): Int = 1
          }
          """
        )

      assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
    }
  }

  fun testQualifiedBinaryLazyAssistedFactoryIsRejected() {
    module.addKotlinStdlibLibrary()
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibEndpoint
          import libtest.LibGenericAssistedExample

          @DependencyGraph
          interface AppGraph {
            @LibEndpoint("selected")
            val factory: Lazy<LibGenericAssistedExample.Factory<Int>>

            @Provides fun provideInt(): Int = 1
          }
          """
        )

      assertEquals(MetroDiagnosticId.INVALID_BINDING, result.diagnostics.first().id)
    }
  }

  fun testProviderOfLazyAssistedFactoryDoesNotUseTheDirectLazyDiagnostic() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Widget(@Assisted val id: String) {
          @AssistedFactory
          interface Factory {
            fun create(id: String): Widget
          }
        }

        @Inject class Consumer(val factory: Provider<Lazy<Widget.Factory>>)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testAssistedClassIsOnlyAvailableForGraphValidation() {
    val file =
      myFixture.configureMetroFile(
        """
        @AssistedInject class Widget(@Assisted val id: String)

        @AssistedFactory
        interface WidgetFactory {
          fun create(id: String): Widget
        }

        @Inject class Screen(val widget: Widget)

        @DependencyGraph
        interface AppGraph {
          val screen: Screen
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val declarations = file.declarationsIncludingNested()
    val widget = declarations.klass("Widget")
    val consumer = checkNotNull(index.consumerEntryAt(declarations.parameter("widget")))

    assertTrue(index.bindingEntriesAt(widget).isEmpty())
    assertTrue(index.bindingsFor(consumer).isEmpty())

    val graph = index.graphs.single()
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(graph).single())
        .requireCompleted()
    assertEquals(listOf(MetroDiagnosticId.INVALID_BINDING), result.diagnostics.map { it.id })
  }

  fun testAssistedClassRequestWithProviderIsRejectedWithoutDuplicate() {
    // The compiler rejects unqualified requests of assisted types even with an explicit
    // provider, as its AssistedTypesCannotBeProvidedWithoutQualifiers fixture shows.
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

          @Provides
          fun provideWidget(factory: WidgetFactory): Widget = factory.create("default")
        }
        """
      )
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.INVALID_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "uses assisted injection" in diagnostic.render())
  }

  fun testExplicitProviderOfInjectClassWinsWithoutDuplicate() {
    // An explicit binding silently shadows the class's own inject constructor, matching the
    // compiler's cache-first lookup.
    val result =
      validate(
        """

        @Inject class Thing

        @DependencyGraph
        interface AppGraph {
          val thing: Thing

          @Provides
          fun provideThing(): Thing = Thing()
        }
        """
      )
    assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
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

  fun testSeparateGraphExtensionFactoryIsInjectableInProvider() {
    val result =
      validateWithoutLibraryResolution(
        """
        interface ChildScope

        class Consumer(val factory: ChildGraph.Factory)

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
          @GraphExtension.Factory
          interface Factory {
            fun create(): ChildGraph
          }
        }

        interface FactoryAccessors {
          val childFactory: ChildGraph.Factory
        }

        @DependencyGraph
        interface AppGraph : FactoryAccessors {
          val consumer: Consumer

          @Provides
          fun provideConsumer(factory: ChildGraph.Factory): Consumer = Consumer(factory)
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val consumer =
      result.bindings.asMap().values.single { it.typeKey.renderedType == "test.Consumer" }
    assertTrue(consumer is KaBinding.Provided)
    assertTrue(consumer.dependencies.any { it.typeKey.renderedType == "test.ChildGraph.Factory" })

    val factory =
      result.bindings.asMap().values.single {
        it.typeKey.renderedType == "test.ChildGraph.Factory"
      }
    assertTrue(factory is KaBinding.GraphExtension && factory.isFactory)
    assertNull(factory.scope)
    assertEquals(listOf("test.AppGraph"), factory.dependencies.map { it.typeKey.renderedType })
  }

  fun testContributedGraphExtensionFactoryAliasKeepsParentOwnershipAndExplicitPrecedence() {
    val file =
      myFixture.configureMetroFile(
        """
        interface ChildScope

        @GraphExtension(ChildScope::class)
        interface ChildGraph : ManualBindings {
          val int: Int

          @ContributesTo(AppScope::class)
          @GraphExtension.Factory
          interface Factory {
            fun createChild(): ChildGraph
          }
        }

        interface ManualBindings : FactoryProvider

        @ContributesTo(AppScope::class)
        interface FactoryProvider {
          val childFactory: ChildGraph.Factory
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Provides fun provideInt(): Int = 3
        }

        @DependencyGraph(AppScope::class)
        interface OverrideGraph {
          @Provides fun provideInt(): Int = 4

          @Provides
          fun explicitFactory(): ChildGraph.Factory = error("unused")
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val parentContext = index.contextsFor(parent).single()
    val child = index.graphs.single { it.name == "ChildGraph" }
    val childContext = index.contextsFor(child).single { it.chain.drop(1) == parentContext.chain }
    val validationService = project.service<MetroGraphValidationService>()
    val parentResult = validationService.validate(file, parentContext).requireCompleted()
    val childResult = validationService.validate(file, childContext).requireCompleted()

    for (result in listOf(parentResult, childResult)) {
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val factory =
        result.bindings.asMap().values.single {
          it.typeKey.renderedType == "test.ChildGraph.Factory"
        }
      assertTrue(factory is KaBinding.Alias)
      assertEquals(listOf("test.AppGraph"), factory.dependencies.map { it.typeKey.renderedType })
      assertTrue(
        result.bindings.any { key, binding ->
          key.renderedType == "test.AppGraph" && binding is KaBinding.GraphInstance
        }
      )
    }

    val overrideGraph = index.graphs.single { it.name == "OverrideGraph" }
    val overrideResult =
      validationService.validate(file, index.contextsFor(overrideGraph).single()).requireCompleted()
    assertTrue(
      overrideResult.diagnostics.joinToString { it.render() },
      overrideResult.diagnostics.isEmpty(),
    )
    val explicitFactory =
      overrideResult.bindings.asMap().values.single {
        it.typeKey.renderedType == "test.ChildGraph.Factory"
      }
    assertTrue(explicitFactory is KaBinding.Provided)
    assertSame(
      file.declarationsIncludingNested().function("explicitFactory"),
      explicitFactory.pointer.element,
    )
  }

  fun testParentAndChildCollectionRootsReuseScopedContributedElements() {
    module.addKotlinStdlibLibrary()
    val file =
      myFixture.configureMetroFile(
        """
        interface Route

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class FirstRoute : Route

        @SingleIn(AppScope::class)
        @ContributesIntoSet(AppScope::class)
        class SecondRoute : Route

        @ContributesTo(AppScope::class)
        interface RouteBindings {
          @Multibinds(allowEmpty = true) fun routes(): Set<Route>
        }

        @GraphExtension
        interface ChildGraph {
          val routes: Set<Route>
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)
    val childResult = results.first().requireCompleted()
    val parentResult = results.last().requireCompleted()

    assertEquals(2, results.size)
    assertTrue(
      childResult.diagnostics.joinToString { it.render() },
      childResult.diagnostics.isEmpty(),
    )
    assertTrue(
      parentResult.diagnostics.joinToString { it.render() },
      parentResult.diagnostics.isEmpty(),
    )
    val parentElements =
      parentResult.bindings.asMap().filterKeys {
        it.qualifier?.classId == MetroClassIds.multibindingElement
      }
    assertEquals(2, parentElements.size)
    assertTrue(parentElements.values.all { it is KaBinding.Alias })
    assertEquals(
      setOf("test.FirstRoute", "test.SecondRoute"),
      parentElements.values.map { it.originClassId?.asFqNameString() }.toSet(),
    )
    assertEquals(parentElements.keys, childResult.parentReservations.keys)
    val parentCollection =
      parentResult.bindings.asMap().values.single {
        it.typeKey.renderedType == "kotlin.collections.Set<test.Route>"
      } as KaBinding.Multibinding
    assertEquals(parentElements.keys, parentCollection.sourceBindings.toSet())
    for (key in parentElements.keys) {
      val childElement = childResult.bindings[key] as KaBinding.GraphDependency
      assertTrue(childElement.isParentScoped)
      assertEquals("test.AppGraph", childElement.ownerKey.renderedType)
    }
  }

  fun testSharedMapCollectionViewsReuseSyntheticElements() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        class Value

        @DependencyGraph
        interface AppGraph {
          val values: Map<String, Value>
          val providers: Map<String, Provider<Value>>

          @Provides @IntoMap @StringKey("only") fun value(): Value = Value()
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val elements =
      result.bindings.asMap().filterKeys {
        it.qualifier?.classId == MetroClassIds.multibindingElement
      }
    assertEquals(1, elements.size)
    assertTrue(elements.values.single() is KaBinding.Provided)
    val collections = result.bindings.asMap().values.filterIsInstance<KaBinding.Multibinding>()
    assertEquals(2, collections.size)
    for (collection in collections) {
      assertEquals(elements.keys, collection.sourceBindings.toSet())
    }
  }

  fun testParentScopedSetContributionIsOwnedByParent() {
    assertParentScopedContributionIsOwnedByParent(
      accessor = "val values: Set<String>",
      contribution =
        "@Provides @IntoSet @SingleIn(AppScope::class) fun parentValue(): String = \"parent\"",
      childContribution = "@Provides @IntoSet fun childValue(): String = \"child\"",
    )
  }

  fun testParentScopedMapContributionIsOwnedByParent() {
    assertParentScopedContributionIsOwnedByParent(
      accessor = "val values: Map<String, String>",
      contribution =
        "@Provides @IntoMap @StringKey(\"parent\") @SingleIn(AppScope::class) " +
          "fun parentValue(): String = \"parent\"",
      childContribution =
        "@Provides @IntoMap @StringKey(\"child\") fun childValue(): String = \"child\"",
    )
  }

  fun testParentFactoryIncludedScopedSetContributionIsOwnedByParent() {
    assertParentScopedContributionIsOwnedByParent(
      accessor = "val values: Set<String>",
      contribution =
        "@Provides @IntoSet @SingleIn(AppScope::class) fun parentValue(): String = \"parent\"",
      childContribution = "@Provides @IntoSet fun childValue(): String = \"child\"",
      factoryIncluded = true,
    )
  }

  fun testParentFactoryIncludedScopedMapContributionIsOwnedByParent() {
    assertParentScopedContributionIsOwnedByParent(
      accessor = "val values: Map<String, String>",
      contribution =
        "@Provides @IntoMap @StringKey(\"parent\") @SingleIn(AppScope::class) " +
          "fun parentValue(): String = \"parent\"",
      childContribution =
        "@Provides @IntoMap @StringKey(\"child\") fun childValue(): String = \"child\"",
      factoryIncluded = true,
    )
  }

  private fun assertParentScopedContributionIsOwnedByParent(
    accessor: String,
    contribution: String,
    childContribution: String,
    factoryIncluded: Boolean = false,
  ) {
    val parentContainer =
      if (factoryIncluded) {
        "@BindingContainer class ParentBindings { $contribution }"
      } else {
        ""
      }
    val parentContribution = if (factoryIncluded) "" else contribution
    val parentFactory =
      if (factoryIncluded) {
        """
        @DependencyGraph.Factory
        interface Factory {
          fun create(@Includes bindings: ParentBindings): AppGraph
        }
        """
      } else {
        ""
      }
    val file =
      myFixture.configureMetroFile(
        """
        $parentContainer

        @GraphExtension
        interface ChildGraph {
          $accessor

          $childContribution
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph

          $parentContribution

          $parentFactory
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)
    val childResult = results.first().requireCompleted()
    val parentResult = results.last().requireCompleted()

    assertTrue(childResult.diagnostics.toString(), childResult.diagnostics.isEmpty())
    assertTrue(parentResult.diagnostics.toString(), parentResult.diagnostics.isEmpty())
    assertTrue(
      "The child should depend on its parent's scoped collection element",
      childResult.bindings.any { key, binding ->
        key.qualifier?.classId == MetroClassIds.multibindingElement &&
          binding is KaBinding.GraphDependency &&
          binding.isParentScoped
      },
    )
    assertTrue(
      "The parent should retain the real element even without its own collection accessor",
      parentResult.bindings.any { key, binding ->
        key.qualifier?.classId == MetroClassIds.multibindingElement && binding is KaBinding.Provided
      },
    )
    assertTrue(
      "The child's collection should also keep contributions declared by the child",
      childResult.bindings.any { key, binding ->
        key.qualifier?.classId == MetroClassIds.multibindingElement && binding is KaBinding.Provided
      },
    )
  }

  fun testGraphPrivateParentBindingIsNotVisibleToChild() {
    val result =
      validate(
        """
        @Inject class ParentValue(val secret: String)

        @GraphExtension
        interface ChildGraph {
          val secret: String
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val parentValue: ParentValue
          val child: ChildGraph

          @GraphPrivate @Provides @SingleIn(AppScope::class)
          fun secret(): String = "parent"
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
  }

  fun testGraphPrivateParentOptionalBindingIsNotVisibleToChild() {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      "package dagger\n\nannotation class BindsOptionalOf",
    )
    myFixture.addFileToProject("java/util/Optional.kt", "package java.util\n\nclass Optional<T>")

    val result =
      validate(
        """
        import dagger.BindsOptionalOf
        import java.util.Optional

        interface Service

        @BindingContainer
        interface ParentBindings {
          @GraphPrivate @BindsOptionalOf fun optionalService(): Service
        }

        @GraphExtension
        interface ChildGraph {
          val service: Optional<Service>
        }

        @DependencyGraph(bindingContainers = [ParentBindings::class])
        interface AppGraph {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
  }

  fun testPublicOptionalBindingSurvivesEarlierPrivateDeclaration() {
    assertPublicOptionalBindingSurvivesPrivateDeclaration(privateFirst = true)
  }

  fun testPublicOptionalBindingSurvivesLaterPrivateDeclaration() {
    assertPublicOptionalBindingSurvivesPrivateDeclaration(privateFirst = false)
  }

  private fun assertPublicOptionalBindingSurvivesPrivateDeclaration(privateFirst: Boolean) {
    project.setMetroOptions("enable-dagger-runtime-interop" to "true")
    myFixture.addFileToProject(
      "dagger/BindsOptionalOf.kt",
      "package dagger\n\nannotation class BindsOptionalOf",
    )
    myFixture.addFileToProject("java/util/Optional.kt", "package java.util\n\nclass Optional<T>")
    val privateDeclaration = "@GraphPrivate @BindsOptionalOf fun privateService(): Service"
    val publicDeclaration = "@BindsOptionalOf fun publicService(): Service"
    val declarations =
      if (privateFirst) {
        "$privateDeclaration\n$publicDeclaration"
      } else {
        "$publicDeclaration\n$privateDeclaration"
      }

    val result =
      validate(
        """
        import dagger.BindsOptionalOf
        import java.util.Optional

        interface Service

        @BindingContainer
        interface ParentBindings {
          $declarations
        }

        @GraphExtension
        interface ChildGraph {
          val service: Optional<Service>
        }

        @DependencyGraph(bindingContainers = [ParentBindings::class])
        interface AppGraph {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testGraphPrivateGetterIsNotVisibleToChild() {
    val result =
      validate(
        """
        @GraphExtension
        interface ChildGraph {
          val secret: String
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph

          @get:GraphPrivate @get:Provides
          val secret: String
            get() = "parent"
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
  }

  fun testPrivateParentBindingDoesNotHidePublicGrandparentBinding() {
    val result =
      validate(
        """
        @GraphExtension
        interface GrandchildGraph {
          val value: String
        }

        @GraphExtension
        interface ChildGraph {
          val grandchild: GrandchildGraph

          @GraphPrivate @Provides fun childValue(): String = "private child"
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph

          @Provides fun parentValue(): String = "public parent"
        }
        """,
        graphName = "GrandchildGraph",
      )

    assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, binding ->
        key.renderedType == "kotlin.String" && !binding.isGraphPrivate
      }
    )
  }

  fun testGraphPrivateSetContributionsStayInTheirOwnerGraph() {
    assertGraphPrivateContributionsStayInOwnerGraph(
      collectionType = "Set<String>",
      privateContribution =
        "@GraphPrivate @Provides @IntoSet fun privateValue(): String = \"private\"",
      publicContribution = "@Provides @IntoSet fun publicValue(): String = \"public\"",
    )
  }

  fun testGraphPrivateMapContributionsStayInTheirOwnerGraph() {
    assertGraphPrivateContributionsStayInOwnerGraph(
      collectionType = "Map<String, String>",
      privateContribution =
        "@GraphPrivate @Provides @IntoMap @StringKey(\"private\") " +
          "fun privateValue(): String = \"private\"",
      publicContribution =
        "@Provides @IntoMap @StringKey(\"public\") " + "fun publicValue(): String = \"public\"",
    )
  }

  private fun assertGraphPrivateContributionsStayInOwnerGraph(
    collectionType: String,
    privateContribution: String,
    publicContribution: String,
  ) {
    val file =
      myFixture.configureMetroFile(
        """
        @GraphExtension
        interface ChildGraph {
          val childValues: $collectionType
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @GraphPrivate @Multibinds val parentValues: $collectionType
          val child: ChildGraph

          $privateContribution
          $publicContribution
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)
    val childResult = results.first().requireCompleted()
    val parentResult = results.last().requireCompleted()

    assertTrue(childResult.diagnostics.toString(), childResult.diagnostics.isEmpty())
    assertTrue(parentResult.diagnostics.toString(), parentResult.diagnostics.isEmpty())
    assertFalse(childResult.bindings.any { _, binding -> binding.isGraphPrivate })
    assertTrue(
      "The parent's private collection declaration should remain private",
      parentResult.bindings.any { _, binding ->
        binding is KaBinding.Multibinding && binding.isGraphPrivate
      },
    )
    assertTrue(
      "The parent's private collection element should remain available to its owner",
      parentResult.bindings.any { _, binding ->
        binding is KaBinding.Provided && binding.isGraphPrivate
      },
    )
    assertTrue(
      "The public parent element should still reach the child's collection",
      childResult.bindings.any { _, binding ->
        binding is KaBinding.Provided && !binding.isGraphPrivate
      },
    )
  }

  fun testPublicParentAliasCanExposePrivateImplementation() {
    val result =
      validate(
        """
        @GraphExtension
        interface ChildGraph {
          val text: CharSequence
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph

          @GraphPrivate @Provides @SingleIn(AppScope::class)
          fun secret(): String = "parent"

          @Binds fun text(value: String): CharSequence
        }
        """,
        graphName = "ChildGraph",
      )

    assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, binding ->
        key.renderedType == "kotlin.CharSequence" && binding is KaBinding.GraphDependency
      }
    )
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "kotlin.String" })
  }

  fun testGraphPrivateParentAliasIsNotVisibleToChild() {
    val result =
      validate(
        """
        @GraphExtension
        interface ChildGraph {
          val text: CharSequence
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph

          @Provides fun value(): String = "parent"

          @GraphPrivate @Binds fun text(value: String): CharSequence
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
  }

  fun testPublicParentAliasRemainsVisibleToGrandchild() {
    val result =
      validate(
        """
        @GraphExtension
        interface GrandchildGraph {
          val text: CharSequence
        }

        @GraphExtension
        interface ChildGraph {
          val grandchild: GrandchildGraph
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val child: ChildGraph

          @GraphPrivate @Provides @SingleIn(AppScope::class)
          fun secret(): String = "parent"

          @Binds fun text(value: String): CharSequence
        }
        """,
        graphName = "GrandchildGraph",
      )

    assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, binding ->
        key.renderedType == "kotlin.CharSequence" && binding is KaBinding.GraphDependency
      }
    )
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "kotlin.String" })
  }

  fun testGraphPrivateFactoryInputIsNotVisibleToChild() {
    val result =
      validate(
        """
        @GraphExtension
        interface ChildGraph {
          val secret: String
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph

          @DependencyGraph.Factory
          interface Factory {
            fun create(@GraphPrivate @Provides secret: String): AppGraph
          }
        }
        """,
        graphName = "ChildGraph",
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
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
    val progress = mutableListOf<GraphValidationProgress>()
    val traversal = validationService.validateWithExtensions(file, leftParent, progress::add)
    assertEquals(listOf("ChildGraph", "LeftParent"), traversal.map { it.graph.name })
    assertEquals(listOf("ChildGraph", "LeftParent"), progress.map { it.graphName })
    assertEquals(listOf(0, 1), progress.map { it.completed })
    assertEquals(listOf(2, 2), progress.map { it.total })
    assertEquals(
      listOf(
        "Validating Metro graph ChildGraph (1 of 2 graphs)",
        "Validating Metro graph LeftParent (2 of 2 graphs)",
      ),
      progress.map { it.message },
    )
    assertTrue(progress.first().covers(leftContext.path))
    assertFalse(progress.first().covers(rightContext.path))
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

  fun testValidatingOneExtensionContextDoesNotValidateItsSiblingContext() {
    val file =
      myFixture.configureMetroFile(
        """
        @GraphExtension
        interface ChildGraph {
          val value: String
        }

        @DependencyGraph
        interface LeftGraph {
          val child: ChildGraph

          @Provides fun value(): String = "left"
        }

        @DependencyGraph
        interface RightGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val childGraph = index.graphs.single { it.name == "ChildGraph" }
    val contexts = index.contextsFor(childGraph)
    val leftContext = contexts.single { it.chain[1].name == "LeftGraph" }
    val rightContext = contexts.single { it.chain[1].name == "RightGraph" }
    val validationService = project.service<MetroGraphValidationService>()

    val results = validationService.validateWithExtensions(file, leftContext)

    assertEquals(listOf(leftContext.path), results.map { it.context.path })
    assertTrue(results.single().requireCompleted().diagnostics.isEmpty())
    assertNull(validationService.cachedResult(file, rightContext))
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

  fun testContributesBindingWithTypeUseQualifier() {
    val result =
      validate(
        """
        @Qualifier
        @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER)
        annotation class Chosen

        interface Page<T>

        @Inject
        @ContributesBinding(AppScope::class, binding = binding<@Chosen Page<*>>())
        class RealPage : Page<String>

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Chosen val page: Page<*>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.asMap().keys.joinToString {
        it.render(short = false, includeQualifier = true)
      },
      result.bindings.any { key, _ ->
        key.renderedType == "test.Page<*>" &&
          key.qualifier?.classId?.shortClassName?.asString() == "Chosen"
      },
    )
  }

  fun testGeneratedContributionProviderDoesNotExposeItsImplementation() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface Service

        @Inject @ContributesBinding(AppScope::class)
        class ServiceImpl : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val implementation: ServiceImpl
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
    assertTrue(
      result.diagnostics.single().render(),
      "ServiceImpl" in result.diagnostics.single().render(),
    )
  }

  fun testGeneratedContributionProviderRequiresConstructorDependenciesOnly() {
    project.setMetroOptions("generate-contribution-providers" to "true")
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        interface MissingMember

        @Inject class ConstructorDependency

        @HasMemberInjections
        abstract class MemberBase {
          @Inject lateinit var member: MissingMember
        }

        @Inject @ContributesBinding(AppScope::class, binding = binding<Service>())
        class ServiceImpl(val constructorDependency: ConstructorDependency) : MemberBase(), Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single()
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(graph).single())
        .requireCompleted()

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.ConstructorDependency" })
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "test.MissingMember" })
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "test.ServiceImpl" })

    val member = index.consumerEntryAt(file.declarationsIncludingNested().property("member"))!!
    assertTrue(index.resolveConsumer(member).perContext.isEmpty())
  }

  fun testGeneratedContributionProviderIgnoresSuspendMemberDependencies() {
    project.setMetroOptions(
      "generate-contribution-providers" to "true",
      "enable-suspend-providers" to "true",
    )

    val result =
      validate(
        """
        interface Service
        class SuspendMember

        @HasMemberInjections
        abstract class MemberBase {
          @Inject lateinit var member: SuspendMember
        }

        @Inject @ContributesBinding(AppScope::class, binding = binding<Service>())
        class ServiceImpl : MemberBase(), Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service

          @Provides suspend fun provideMember(): SuspendMember = SuspendMember()
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "test.SuspendMember" })
  }

  fun testGeneratedContributionProviderStillRequiresConstructorDependencies() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface Service
        interface MissingConstructorDependency

        @Inject @ContributesBinding(AppScope::class)
        class ServiceImpl(val dependency: MissingConstructorDependency) : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
    assertTrue(
      result.diagnostics.single().render(),
      "MissingConstructorDependency" in result.diagnostics.single().render(),
    )
  }

  fun testExposedContributionProviderStillRequiresInjectedMembers() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface Service
        interface MissingMember

        @HasMemberInjections
        abstract class MemberBase {
          @Inject lateinit var member: MissingMember
        }

        @ExposeImplBinding
        @Inject
        @ContributesBinding(AppScope::class, binding = binding<Service>())
        class ServiceImpl : MemberBase(), Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
    assertTrue(
      result.diagnostics.single().render(),
      "MissingMember" in result.diagnostics.single().render(),
    )
  }

  fun testExposedContributionProviderRetainsItsImplementation() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface Service

        @ExposeImplBinding @Inject @ContributesBinding(AppScope::class)
        class ServiceImpl : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val implementation: ServiceImpl
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.ServiceImpl" })
  }

  fun testPrivateInjectConstructorRetainsItsContributedImplementation() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface Service

        @ContributesBinding(AppScope::class)
        class ServiceImpl @Inject private constructor() : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
          val implementation: ServiceImpl
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.ServiceImpl" })
  }

  fun testGeneratedContributionProvidersParticipateInAnvilRanks() {
    project.setMetroOptions(
      "generate-contribution-providers" to "true",
      "enable-dagger-anvil-interop" to "true",
      "custom-contributes-binding" to "test/RankedBinding",
    )

    val result =
      validate(
        """
        import kotlin.reflect.KClass

        annotation class RankedBinding(val scope: KClass<*>, val rank: Int = 0)

        interface Service

        @ExposeImplBinding @Inject @RankedBinding(AppScope::class, rank = 50)
        class LowerService : Service

        @Inject @RankedBinding(AppScope::class, rank = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    var serviceBinding: KaBinding? = null
    result.bindings.forEach { key, binding ->
      if (key.renderedType == "test.Service") {
        serviceBinding = binding
      }
    }
    assertTrue(serviceBinding is KaBinding.Provided)
    assertEquals("HigherService", serviceBinding?.implementationName)
  }

  fun testGeneratedContributionProvidersParticipateInPrioritiesWithoutInterop() {
    project.setMetroOptions(
      "generate-contribution-providers" to "true",
      "custom-contributes-binding" to "test/PrioritizedBinding",
    )

    val result =
      validate(
        """
        import kotlin.reflect.KClass

        annotation class PrioritizedBinding(val scope: KClass<*>, val priority: Int)

        interface Service

        @Inject @PrioritizedBinding(AppScope::class, priority = 50)
        class LowerService : Service

        @Inject @PrioritizedBinding(AppScope::class, priority = 100)
        class HigherService : Service

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val service: Service
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val serviceBinding =
      result.bindings.asMap().values.single {
        it.typeKey.renderedType == "test.Service"
      }
    assertTrue(serviceBinding is KaBinding.Provided)
    assertEquals("HigherService", serviceBinding.implementationName)
    assertEquals(100, serviceBinding.priority)
  }

  fun testContributedAssistedFactoryRetainsItsTargetsNonAssistedDependencies() {
    project.setMetroOptions("generate-contribution-providers" to "true")

    val result =
      validate(
        """
        interface PublicFactory {
          fun create(id: String): Widget
        }

        @Inject class RequiredDependency

        @AssistedInject
        class Widget(@Assisted val id: String, val dependency: RequiredDependency)

        @AssistedFactory @ContributesBinding(AppScope::class)
        interface WidgetFactory : PublicFactory {
          override fun create(id: String): Widget
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val factory: PublicFactory
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.RequiredDependency" })
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.WidgetFactory" })
  }

  fun testContributedInheritedFactoryAliasDiscoversNestedSourceFactoryWithoutLibraries() {
    val settings = MetroSettings.getInstance(project).state
    val previous = settings.resolveFromLibraries
    settings.resolveFromLibraries = false
    try {
      val file =
        myFixture.configureMetroFile(
          """
          @AssistedInject
          class Inner<T>(@Assisted val id: String, val value: T) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(id: String): Inner<T>
            }
          }

          @AssistedInject
          class Outer<T>(@Assisted val id: String, val inner: Inner.Factory<T>)

          interface PublicFactory<T> {
            fun create(id: String): Outer<T>
          }

          @AssistedFactory @ContributesBinding(AppScope::class)
          interface ContributedFactory : PublicFactory<Int>

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: PublicFactory<Int>

            @Provides fun provideInt(): Int = 1
          }
          """
        )
      val index = project.service<MetroResolutionService>().index(file)
      // The concrete factory is reached through the contribution's Alias binding, not a source
      // consumer that directly requests ContributedFactory or Inner.Factory<Int>.
      assertTrue(index.consumers.none { it.key.renderedType == "test.ContributedFactory" })
      assertTrue(index.consumers.none { it.key.renderedType == "test.Inner.Factory<kotlin.Int>" })
      val graph = index.graphs.single { it.name == "AppGraph" }
      val result =
        project
          .service<MetroGraphValidationService>()
          .validate(file, index.contextsFor(graph).single())
          .requireCompleted()

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(
        result.bindings.any { key, binding ->
          key.renderedType == "test.PublicFactory<kotlin.Int>" && binding is KaBinding.Alias
        }
      )
      val factoryTypes =
        result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().map {
          it.typeKey.renderedType
        }
      assertEquals(
        setOf("test.ContributedFactory", "test.Inner.Factory<kotlin.Int>"),
        factoryTypes.toSet(),
      )
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "kotlin.Int" })
    } finally {
      settings.resolveFromLibraries = previous
    }
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

  fun testGenericGraphSupertypeAccessorsUseConcreteTypes() {
    val result =
      validate(
        """
        @Inject class Service

        interface BaseGraph<T> {
          val service: T
        }

        @DependencyGraph
        interface AppGraph : BaseGraph<Service>
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.topology!!.sortedKeys.any { it.renderedType == "test.Service" })
  }

  fun testGenericSupertypeProvidersAreSpecializedPerGraph() {
    val file =
      myFixture.configureMetroFile(
        """
        interface GenericBase<T> {
          val value: T

          @Provides fun provideValue(): T = error("unused")
        }

        @DependencyGraph
        interface StringGraph : GenericBase<String>

        @DependencyGraph
        interface IntGraph : GenericBase<Int>
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val service = project.service<MetroGraphValidationService>()
    for ((graphName, expectedType) in
      listOf("StringGraph" to "kotlin.String", "IntGraph" to "kotlin.Int")) {
      val graph = index.graphs.single { it.name == graphName }
      val context = index.contextsFor(graph).single()
      val queryContext = checkNotNull(index.queryContext(context))
      val indexedProvidedTypes =
        index.bindingsInContext(queryContext).filterIsInstance<KaBinding.Provided>().map {
          it.typeKey.renderedType
        }
      assertEquals(listOf(expectedType), indexedProvidedTypes)
      val result = service.validate(file, context).requireCompleted()

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

  fun testGenericSupertypeProvidersWithConcreteReturnDoNotKeepRawParameters() {
    val file =
      myFixture.configureMetroFile(
        """
        interface GenericBase<T> {
          @Provides fun provideText(value: T): String = value.toString()
        }

        @DependencyGraph
        interface IntGraph : GenericBase<Int> {
          val text: String

          @Provides fun provideInt(): Int = 1
        }

        @DependencyGraph
        interface BooleanGraph : GenericBase<Boolean> {
          val text: String

          @Provides fun provideBoolean(): Boolean = true
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val validation = project.service<MetroGraphValidationService>()

    for ((graphName, expectedParameterType) in
      listOf("IntGraph" to "kotlin.Int", "BooleanGraph" to "kotlin.Boolean")) {
      val graph = index.graphs.single { it.name == graphName }
      val result = validation.validate(file, index.contextsFor(graph).single()).requireCompleted()
      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val stringBindings = mutableListOf<KaBinding>()
      result.bindings.forEach { key, binding ->
        if (key.renderedType == "kotlin.String") stringBindings += binding
      }
      assertEquals(1, stringBindings.size)
      assertEquals(
        expectedParameterType,
        stringBindings.single().dependencies.single().typeKey.renderedType,
      )
    }

    val parameter = file.declarationsIncludingNested().parameter("value")
    val consumers = index.consumerEntriesAt(parameter)
    assertEquals(
      setOf("kotlin.Int", "kotlin.Boolean"),
      consumers.mapTo(mutableSetOf()) { it.key.renderedType },
    )
    assertEquals(
      setOf("IntGraph", "BooleanGraph"),
      consumers.mapTo(mutableSetOf()) { consumer ->
        index.resolveConsumer(consumer).perContext.keys.single().graph.name
      },
    )
    assertNull(index.consumerEntryAt(parameter))
  }

  fun testGenericSupertypeBindsWithConcreteReturnDoesNotKeepRawBinding() {
    val result =
      validate(
        """
        interface Service
        @Inject class RealService : Service

        interface GenericBase<T : Service> {
          @Binds fun bindService(value: T): Service
        }

        @DependencyGraph
        interface AppGraph : GenericBase<RealService> {
          val service: Service
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.RealService" })
  }

  fun testGenericSupertypeBindsReceiverUsesItsConcreteDependency() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service
        @Inject class RealService : Service

        interface GenericBase<T : Service> {
          @Binds val T.boundService: Service
        }

        @DependencyGraph
        interface AppGraph : GenericBase<RealService> {
          val service: Service
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, index.contextsFor(graph).single())
        .requireCompleted()

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val receiver =
      file.declarationsIncludingNested().property("boundService").receiverTypeReference!!
    val consumer = checkNotNull(index.consumerEntryAt(receiver))
    assertEquals("test.RealService", consumer.key.renderedType)
    assertEquals(graph.declarationId, consumer.graphId)
  }

  fun testSourceGenericAssistedFactoriesUseConcreteRequestedTypes() {
    val result =
      validate(
        """
        @AssistedInject
        class Example<T>(@Assisted val input: T, val dependency: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(input: T): Example<T>
          }

          @AssistedFactory
          fun interface Factory2 {
            fun create(input: Int): Example<Int>
          }
        }

        @AssistedInject
        class Different<T, R>(@Assisted val input: T, val dependency: R) {
          @AssistedFactory
          fun interface Factory<T, R> {
            fun create(input: T): Different<T, R>
          }

          @AssistedFactory
          fun interface Factory2<T> {
            fun create(input: Int): Different<Int, T>
          }
        }

        interface BaseFactory<T> {
          fun create(input: T): Example<T>
        }

        @AssistedFactory
        interface InheritedFactory<T> : BaseFactory<T>

        @DependencyGraph
        interface AppGraph {
          val first: Example.Factory<Int>
          val second: Example.Factory2
          val third: Different.Factory<Int, String>
          val fourth: Different.Factory2<String>
          val inherited: InheritedFactory<Int>

          @Provides fun provideInt(): Int = 1
          @Provides fun provideString(): String = "ready"
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factories = mutableListOf<KaBinding.AssistedFactory>()
    result.bindings.forEach { _, binding ->
      if (binding is KaBinding.AssistedFactory) factories += binding
    }
    assertEquals(5, factories.size)
    assertEquals(
      setOf("kotlin.Int", "kotlin.String"),
      factories.flatMapTo(mutableSetOf()) { factory ->
        factory.targetConstructorDependencies.map { it.typeKey.renderedType }
      },
    )
    assertTrue(factories.none { it.targetTypeKey?.renderedType?.contains("<T") == true })
  }

  fun testGenericAssistedFactoryRequestedFromInjectedConstructorInAnotherFile() {
    myFixture.addFileToProject(
      "test/GenericFactory.kt",
      """
      package test

      import dev.zacsweers.metro.*

      @AssistedInject
      class Example<T>(@Assisted val id: String, val dependency: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): Example<T>
        }
      }
      """
        .trimIndent(),
    )
    val graph =
      myFixture.configureMetroFile(
        """
        @Inject class Consumer(val factory: Example.Factory<Int>)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer

          @Provides fun provideInt(): Int = 1
        }
        """
      )
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val index = project.service<MetroResolutionService>().index(graph)
    val declaration = index.graphs.single { it.name == "AppGraph" }
    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(graph, index.contextsFor(declaration).single())
        .requireCompleted()

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, _ -> key.renderedType == "test.Example.Factory<kotlin.Int>" }
    )
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "kotlin.Int" })
  }

  fun testWrappedGenericAssistedFactoryRequestsUseConcreteTypes() {
    val result =
      validate(
        """
        @AssistedInject
        class Example<T>(@Assisted val id: String, val dependency: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Example<T>
          }
        }

        @Inject class Consumer(val factory: Provider<Example.Factory<Int>>)

        @DependencyGraph
        interface AppGraph {
          val factory: Provider<Example.Factory<String>>
          val consumer: Consumer

          @Provides fun provideInt(): Int = 1
          @Provides fun provideString(): String = "ready"
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, _ -> key.renderedType == "test.Example.Factory<kotlin.Int>" }
    )
    assertTrue(
      result.bindings.any { key, _ -> key.renderedType == "test.Example.Factory<kotlin.String>" }
    )
  }

  fun testUnusedExpandingGenericAssistedFactoryDoesNotPreventValidation() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<List<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val text: String

          @Provides fun provideText(): String = "ready"
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.asMap().values.none { it is KaBinding.AssistedFactory })
  }

  fun testExpandingGenericAssistedFactoryReportsIncompleteAnalysis() {
    module.addKotlinStdlibLibrary()
    val result =
      validateResult(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<List<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>
        }
        """
      )

    assertTrue(result.javaClass.simpleName, result is KaGraphValidationResult.Incomplete)
    result as KaGraphValidationResult.Incomplete
    assertTrue(result.reason.isNotBlank())
  }

  fun testExponentiallyGrowingAssistedFactoryReportsIncompleteAnalysis() {
    module.addKotlinStdlibLibrary()
    val result =
      validateResult(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<Pair<T, T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>
        }
        """
      )

    assertTrue(result.javaClass.simpleName, result is KaGraphValidationResult.Incomplete)
    result as KaGraphValidationResult.Incomplete
    assertTrue(result.reason.isNotBlank())
  }

  fun testAlternatingSourceAndBinaryFactoryExpansionReportsIncompleteAnalysis() {
    module.addKotlinStdlibLibrary()
    module.withMetroLibFixtureLibrary {
      val result =
        validateResult(
          """
          import libtest.LibGenericAssistedExample

          @AssistedInject
          class Node<T>(
            @Assisted val id: String,
            val next: LibGenericAssistedExample.Factory<Node.Factory<List<T>>>,
          ) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(id: String): Node<T>
            }
          }

          @DependencyGraph
          interface AppGraph {
            val factory: Node.Factory<Int>
          }
          """
        )

      assertTrue(result.javaClass.simpleName, result is KaGraphValidationResult.Incomplete)
      result as KaGraphValidationResult.Incomplete
      assertTrue(result.reason.isNotBlank())
    }
  }

  fun testExplicitFactoryProviderTerminatesGenericExpansion() {
    module.addKotlinStdlibLibrary()
    val result =
      validate(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<List<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>

          @Provides
          fun terminalFactory(): Node.Factory<List<List<List<Int>>>> = error("unused")
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factories = result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>()
    assertEquals(
      setOf(
        "test.Node.Factory<kotlin.Int>",
        "test.Node.Factory<kotlin.collections.List<kotlin.Int>>",
        "test.Node.Factory<kotlin.collections.List<kotlin.collections.List<kotlin.Int>>>",
      ),
      factories.mapTo(mutableSetOf()) { it.typeKey.renderedType },
    )
    val terminalType =
      "test.Node.Factory<kotlin.collections.List<kotlin.collections.List<kotlin.collections.List<kotlin.Int>>>>"
    assertTrue(
      result.bindings.any { key, binding ->
        key.renderedType == terminalType && binding is KaBinding.Provided
      }
    )
  }

  fun testDefaultedExpandingFactoryDependencyStillReportsIncompleteAnalysis() {
    module.addKotlinStdlibLibrary()
    val result =
      validateResult(
        """
        @AssistedInject
        class Node<T>(
          @Assisted val id: String,
          val next: Factory<List<T>> = error("unused"),
        ) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>
        }
        """
      )

    // A default only permits an absent binding. The implicit factory exists, so a truncated
    // expansion cannot be treated as absence or successful validation.
    assertTrue(result.javaClass.simpleName, result is KaGraphValidationResult.Incomplete)
    result as KaGraphValidationResult.Incomplete
    assertTrue(result.reason.isNotBlank())
  }

  fun testIncompleteExtensionPreventsSuccessfulParentValidationAndIsCached() {
    module.addKotlinStdlibLibrary()
    val file =
      myFixture.configureMetroFile(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<List<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @GraphExtension
        interface ChildGraph {
          val factory: Node.Factory<Int>
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().index(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val child = index.graphs.single { it.name == "ChildGraph" }
    val parentContext = index.contextsFor(parent).single()
    val childContext = index.contextsFor(child).single()
    val service = project.service<MetroGraphValidationService>()

    val parentResult = service.validate(file, parentContext)
    assertTrue(
      parentResult.javaClass.simpleName,
      parentResult is KaGraphValidationResult.Incomplete,
    )
    parentResult as KaGraphValidationResult.Incomplete
    assertTrue(parentResult.reason, "ChildGraph" in parentResult.reason)

    val cachedChild = checkNotNull(service.cachedResult(file, childContext))
    val childResult = cachedChild.result
    assertTrue(childResult.javaClass.simpleName, childResult is KaGraphValidationResult.Incomplete)
    assertFalse(cachedChild.stale)
    assertSame(childResult, service.validate(file, childContext))
    assertSame(parentResult, service.validate(file, parentContext))
    assertSame(parentResult, checkNotNull(service.cachedResult(file, parentContext)).result)
  }

  fun testGenericAssistedFactoryWithConstantTypeResetCompletes() {
    val result =
      validate(
        """
        @AssistedInject
        class Node<T>(@Assisted val id: String, val next: Factory<String>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Node<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factoryTypes =
      result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().map {
        it.typeKey.renderedType
      }
    assertEquals(
      setOf("test.Node.Factory<kotlin.Int>", "test.Node.Factory<kotlin.String>"),
      factoryTypes.toSet(),
    )
  }

  fun testGenericAssistedFactoryWithPermutedTypeParametersCompletes() {
    val result =
      validate(
        """
        @AssistedInject
        class Node<A, B>(@Assisted val id: String, val next: Factory<B, A>) {
          @AssistedFactory
          fun interface Factory<A, B> {
            fun create(id: String): Node<A, B>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Node.Factory<Int, String>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factoryTypes =
      result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().map {
        it.typeKey.renderedType
      }
    assertEquals(
      setOf(
        "test.Node.Factory<kotlin.Int, kotlin.String>",
        "test.Node.Factory<kotlin.String, kotlin.Int>",
      ),
      factoryTypes.toSet(),
    )
  }

  fun testNestedGenericAssistedFactoriesUseConcreteDependenciesTransitively() {
    val result =
      validateWithoutLibraryResolution(
        """
        @Qualifier annotation class Chosen

        @AssistedInject
        class Inner<T>(@Assisted val id: String, @Chosen val value: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Inner<T>
          }
        }

        @AssistedInject
        class Middle<T>(@Assisted val id: String, val inner: Provider<Inner.Factory<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Middle<T>
          }
        }

        @AssistedInject
        class Outer<T>(@Assisted val id: String, val middle: Middle.Factory<T>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Outer<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Outer.Factory<Int>

          @Provides @Chosen fun provideInt(): Int = 1
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factoryTypes =
      result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().map {
        it.typeKey.renderedType
      }
    assertEquals(
      setOf(
        "test.Inner.Factory<kotlin.Int>",
        "test.Middle.Factory<kotlin.Int>",
        "test.Outer.Factory<kotlin.Int>",
      ),
      factoryTypes.toSet(),
    )
  }

  fun testMutuallyDependentGenericAssistedFactoriesDoNotRecurseIndefinitely() {
    val result =
      validateWithoutLibraryResolution(
        """
        @AssistedInject
        class Left<T>(@Assisted val id: String, val right: Right.Factory<T>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Left<T>
          }
        }

        @AssistedInject
        class Right<T>(@Assisted val id: String, val left: Provider<Left.Factory<T>>) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Right<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Left.Factory<Int>
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertEquals(
      2,
      result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().size,
    )
  }

  fun testIncludedGenericContainerFactoryConsumersResolveWithoutLibraries() {
    val result =
      validateWithoutLibraryResolution(
        """
        interface ReceiverFactory
        interface AliasFactory
        class ParameterResult

        @AssistedInject
        class Widget<T>(@Assisted val id: String, val value: T) {
          @AssistedFactory
          fun interface Factory<T> : ReceiverFactory, AliasFactory {
            fun create(id: String): Widget<T>
          }
        }

        @BindingContainer
        interface FactoryBindings<P, R, A> {
          @Provides
          fun parameter(factory: Widget.Factory<P>): ParameterResult = ParameterResult()

          @Binds val Widget.Factory<R>.receiver: ReceiverFactory

          @Binds fun alias(factory: Widget.Factory<A>): AliasFactory
        }

        @DependencyGraph
        interface AppGraph {
          val parameter: ParameterResult
          val receiver: ReceiverFactory
          val alias: AliasFactory

          @Provides fun provideInt(): Int = 1
          @Provides fun provideString(): String = "ready"
          @Provides fun provideBoolean(): Boolean = true

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes bindings: FactoryBindings<Int, String, Boolean>): AppGraph
          }
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factoryTypes =
      result.bindings.asMap().values.filterIsInstance<KaBinding.AssistedFactory>().map {
        it.typeKey.renderedType
      }
    assertEquals(
      setOf(
        "test.Widget.Factory<kotlin.Int>",
        "test.Widget.Factory<kotlin.String>",
        "test.Widget.Factory<kotlin.Boolean>",
      ),
      factoryTypes.toSet(),
    )
  }

  fun testGenericGraphInjectorSpecializesFactoryTypedInheritedMembers() {
    val result =
      validate(
        """
        @AssistedInject
        class Widget<T>(@Assisted val id: String, val value: T) {
          @AssistedFactory
          fun interface Factory<T> {
            fun create(id: String): Widget<T>
          }
        }

        @HasMemberInjections
        open class MemberBase<T> {
          @Inject lateinit var factory: Widget.Factory<T>
        }

        class Target<T> : MemberBase<T>()

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target<Int>)

          @Provides fun provideInt(): Int = 1
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(
      result.bindings.any { key, _ ->
        key.renderedType == "test.Widget.Factory<kotlin.Int>"
      }
    )
  }

  fun testGenericAssistedFactoryPreservesQualifiedSuspendDependencies() {
    project.setMetroOptions("enable-suspend-providers" to "true")

    val result =
      validate(
        """
        @Qualifier annotation class Endpoint

        @AssistedInject
        class Example<T>(@Assisted val id: String, @Endpoint val dependency: T) {
          @AssistedFactory
          interface Factory<T> {
            suspend fun create(id: String): Example<T>
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Example.Factory<Int>

          @Provides @Endpoint suspend fun provideInt(): Int = 1
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    val factories = mutableListOf<KaBinding.AssistedFactory>()
    result.bindings.forEach { _, binding ->
      if (binding is KaBinding.AssistedFactory) factories += binding
    }
    val factory = factories.single()
    assertTrue(factory.factoryFunctionIsSuspend)
    assertEquals("kotlin.Int", factory.targetConstructorDependencies.single().typeKey.renderedType)
    assertNotNull(factory.targetConstructorDependencies.single().typeKey.qualifier)
  }

  fun testGenericGraphInjectorSpecializesInheritedMemberDependencies() {
    val result =
      validate(
        """
        @HasMemberInjections
        open class MemberBase<T> {
          @Inject lateinit var dependency: T
        }

        class Target<T> : MemberBase<T>()

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target<String>)

          @Provides fun provideString(): String = "ready"
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "kotlin.String" })
  }

  fun testGraphIndexTracksChangesToUnannotatedSupertypeFiles() {
    val base =
      myFixture.addFileToProject(
        "test/BaseGraph.kt",
        """
        package test

        interface BaseGraph {
          val original: String
        }
        """
          .trimIndent(),
      ) as KtFile
    val graph = myFixture.configureMetroFile("@DependencyGraph interface AppGraph : BaseGraph")
    val service = project.service<MetroResolutionService>()
    assertEquals(
      listOf("kotlin.String"),
      service.index(graph).consumers.map { it.key.renderedType },
    )

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(base))
    WriteCommandAction.runWriteCommandAction(project) {
      document.setText(
        """
        package test

        interface BaseGraph {
          val replacement: Int
        }
        """
          .trimIndent()
      )
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    assertEquals(
      listOf("kotlin.Int"),
      service.index(graph).consumers.map { it.key.renderedType },
    )
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
    // Project-file fixtures can leave the second document uncommitted.
    PsiDocumentManager.getInstance(project).commitAllDocuments()

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

  fun testBinaryAssistedFactoryResolvesItsTransitiveDependencies() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibAssistedWidgetFactory

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: LibAssistedWidgetFactory
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testBinaryGenericAssistedFactoriesUseConcreteRequestedTypes() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibGenericAssistedExample
          import libtest.LibGenericAssistedDifferent
          import libtest.LibInheritedGenericAssistedFactory
          import libtest.LibWrappedGenericAssisted

          @DependencyGraph
          interface AppGraph {
            val first: LibGenericAssistedExample.Factory<Int>
            val second: LibGenericAssistedExample.Factory2
            val third: LibGenericAssistedDifferent.Factory<Int, String>
            val fourth: LibGenericAssistedDifferent.Factory2<String>
            val inherited: LibInheritedGenericAssistedFactory<Int>
            val wrapped: LibWrappedGenericAssisted.Factory<Int>

            @Provides fun provideInt(): Int = 1
            @Provides fun provideString(): String = "ready"
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val factories = mutableListOf<KaBinding.AssistedFactory>()
      result.bindings.forEach { _, binding ->
        if (binding is KaBinding.AssistedFactory) factories += binding
      }
      assertEquals(6, factories.size)
      assertTrue(factories.none { it.targetTypeKey?.renderedType?.contains("<T") == true })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "kotlin.Int" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "kotlin.String" })
    }
  }

  fun testBinaryGenericAssistedFactoryPreservesQualifiedDependencies() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibEndpoint
          import libtest.LibQualifiedGenericAssisted

          @DependencyGraph
          interface AppGraph {
            val factory: LibQualifiedGenericAssisted.Factory<Int>

            @Provides @LibEndpoint("primary") fun provideInt(): Int = 1
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val factories = mutableListOf<KaBinding.AssistedFactory>()
      result.bindings.forEach { _, binding ->
        if (binding is KaBinding.AssistedFactory) factories += binding
      }
      assertNotNull(factories.single().targetConstructorDependencies.single().typeKey.qualifier)
    }
  }

  fun testBinaryGenericSuspendAssistedFactoryUsesConcreteDependency() {
    project.setMetroOptions("enable-suspend-providers" to "true")
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibSuspendGenericAssisted

          @DependencyGraph
          interface AppGraph {
            val factory: LibSuspendGenericAssisted.Factory<Int>

            @Provides suspend fun provideInt(): Int = 1
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      val factories = mutableListOf<KaBinding.AssistedFactory>()
      result.bindings.forEach { _, binding ->
        if (binding is KaBinding.AssistedFactory) factories += binding
      }
      val factory = factories.single()
      assertTrue(factory.factoryFunctionIsSuspend)
      assertEquals(
        "kotlin.Int",
        factory.targetConstructorDependencies.single().typeKey.renderedType,
      )
    }
  }

  fun testSourceGenericAssistedFactoryResolvesConsumerModuleBinaryDependencies() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibClientWithDeps

          @AssistedInject
          class Example<T>(@Assisted val id: String, val dependency: T) {
            @AssistedFactory
            fun interface Factory<T> {
              fun create(id: String): Example<T>
            }
          }

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val factory: Example.Factory<LibClientWithDeps>
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
    }
  }

  fun testHintedBinaryContributionResolvesItsTransitiveDependencies() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibTransitiveService

          @DependencyGraph(AppScope::class)
          interface AppGraph {
            val service: LibTransitiveService
          }
          """
        )

      assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibClientWithDeps" })
      assertTrue(result.bindings.any { key, _ -> key.renderedType == "libtest.LibHttpClient" })
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

  fun testValidationCancelsWhenRetainedGraphDisappears() {
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

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val graphNameOffset = document.text.indexOf("AppGraph")
    WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(
        graphNameOffset,
        graphNameOffset + "AppGraph".length,
        "RenamedGraph",
      )
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()

    val cached = validationService.cachedResult(file, context)!!
    assertSame(result, cached.result)
    assertTrue(cached.stale)

    try {
      validationService.validate(file, context)
      fail("Expected stale graph context validation to be cancelled")
    } catch (e: CancellationException) {
      assertEquals("Metro graph context is no longer current", e.message)
    }

    try {
      validationService.validateWithExtensions(file, graph)
      fail("Expected stale graph declaration validation to be cancelled")
    } catch (e: CancellationException) {
      assertEquals("Metro graph declaration is no longer current", e.message)
    }
  }
}
