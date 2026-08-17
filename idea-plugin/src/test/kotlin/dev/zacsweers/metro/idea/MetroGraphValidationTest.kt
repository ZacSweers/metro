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
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.graph.runGraphValidation
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.index.retryCancelledIndexBuild
import dev.zacsweers.metro.idea.model.KaBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtFile

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

    assertTrue(result is KaGraphValidationResult.InternalError)
    result as KaGraphValidationResult.InternalError
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

  fun testGeneratedContributionProviderRetainsConstructorAndInheritedMemberDependencies() {
    project.setMetroOptions("generate-contribution-providers" to "true")
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @Inject class ConstructorDependency
        @Inject class MemberDependency

        @HasMemberInjections
        abstract class MemberBase {
          @Inject lateinit var member: MemberDependency
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
    assertTrue(result.bindings.any { key, _ -> key.renderedType == "test.MemberDependency" })
    assertFalse(result.bindings.any { key, _ -> key.renderedType == "test.ServiceImpl" })

    val member = index.consumerEntryAt(file.declarationsIncludingNested().property("member"))!!
    assertEquals(
      listOf("AppGraph"),
      index.resolveConsumer(member).perContext.keys.map { it.graph.name },
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
