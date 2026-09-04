// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.GraphValidationProgress
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration

class MetroDynamicGraphTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  fun testDynamicBindingContainersReplaceStaticBindings() {
    val file =
      myFixture.configureMetroFile(
        """
        @BindingContainer
        object RealBindings {
          @Provides fun provideReal(): String = "real"
        }

        @BindingContainer
        object FakeBindings {
          @Provides fun provideFake(): String = "fake"
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface AppGraph {
          val value: String
        }

        val dynamicGraph = createDynamicGraph<AppGraph>(FakeBindings)
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val contexts = index.contextsFor(graph)
    val staticContext = contexts.single { it.dynamicGraph == null }
    val dynamicContext = contexts.single { it.dynamicGraph != null }
    val consumer =
      checkNotNull(index.consumerEntryAt(file.declarationsIncludingNested().property("value")))

    assertEquals(listOf("provideReal"), index.providerNames(consumer, staticContext))
    assertEquals(listOf("provideFake"), index.providerNames(consumer, dynamicContext))
    assertTrue(dynamicContext.contextPointer.element is KtCallExpression)

    val result =
      project
        .service<MetroGraphValidationService>()
        .validate(file, dynamicContext)
        .requireCompleted()
    assertTrue(result.diagnostics.isEmpty())
  }

  fun testEquivalentCallsShareAContextWithinAFileButNotAcrossFiles() {
    // Install the resolution listener before creating files so every caller is enrolled.
    val service = project.service<MetroResolutionService>()
    val declarations =
      myFixture.addFileToProject(
        "test/Graph.kt",
        """
        package test

        import dev.zacsweers.metro.*

        @BindingContainer object FakeBindings
        @DependencyGraph interface AppGraph
        """
          .trimIndent(),
      ) as KtFile
    val firstCalls =
      myFixture.addFileToProject(
        "test/FirstCalls.kt",
        """
        package test

        import dev.zacsweers.metro.*

        val first: AppGraph = createDynamicGraph(FakeBindings)
        val second = createDynamicGraph<AppGraph>(FakeBindings)
        """
          .trimIndent(),
      ) as KtFile
    val secondCalls =
      myFixture.addFileToProject(
        "test/SecondCalls.kt",
        """
        package test

        import dev.zacsweers.metro.*

        val third = createDynamicGraph<AppGraph>(FakeBindings)
        """
          .trimIndent(),
      ) as KtFile

    val index = service.awaitIndex(declarations)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val dynamicContexts = index.contextsFor(graph).filter { it.dynamicGraph != null }

    assertEquals(2, dynamicContexts.size)
    assertEquals(
      setOf(firstCalls.virtualFile, secondCalls.virtualFile),
      dynamicContexts.mapTo(mutableSetOf()) { it.dynamicGraph!!.id.callerFile },
    )
    val progress = GraphValidationProgress(dynamicContexts.first().path)
    assertTrue(progress.covers(dynamicContexts.first().path))
    assertFalse(progress.covers(dynamicContexts.last().path))
  }

  fun testDynamicGraphFactoryUsesReturnedGraphAndConcreteContainerTypes() {
    val file =
      myFixture.configureMetroFile(
        """
        @BindingContainer
        object RealBindings {
          @Provides fun provideReal(): String = "real"
        }

        @BindingContainer
        class GenericBindings<T>(private val value: T) {
          @Provides fun provideValue(): T = value
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface AppGraph {
          val value: String

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(): AppGraph
          }
        }

        val factory = createDynamicGraphFactory<AppGraph.Factory>(GenericBindings("fake"))
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val context = index.contextsFor(graph).single { it.dynamicGraph != null }
    val dynamicGraph = checkNotNull(context.dynamicGraph)
    val consumer =
      checkNotNull(index.consumerEntryAt(file.declarationsIncludingNested().property("value")))

    assertTrue(dynamicGraph.isFactory)
    assertEquals("test.AppGraph.Factory", dynamicGraph.id.requestedTypeClassId.asFqNameString())
    assertEquals(
      listOf("test.GenericBindings<kotlin.String>"),
      dynamicGraph.containerKeys.map { it.renderedType },
    )
    assertEquals(listOf("provideValue"), index.providerNames(consumer, context))
    assertTrue(
      project
        .service<MetroGraphValidationService>()
        .validate(file, context)
        .requireCompleted()
        .diagnostics
        .isEmpty()
    )
  }

  fun testDynamicBindingsFlowIntoGraphExtensions() {
    val file =
      myFixture.configureMetroFile(
        """
        @BindingContainer
        object RealBindings {
          @Provides fun provideReal(): String = "real"
        }

        @BindingContainer
        object FakeBindings {
          @Provides fun provideFake(): String = "fake"
        }

        @GraphExtension
        interface ChildGraph {
          val value: String
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface AppGraph {
          val child: ChildGraph
        }

        val dynamicGraph = createDynamicGraph<AppGraph>(FakeBindings)
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val child = index.graphs.single { it.name == "ChildGraph" }
    val parentContext = index.contextsFor(parent).single { it.dynamicGraph != null }
    val childContext = index.contextsFor(child).single { it.dynamicGraph != null }
    val childConsumer =
      checkNotNull(index.consumerEntryAt(file.declarationsIncludingNested().property("value")))

    assertSame(parentContext.dynamicGraph, childContext.dynamicGraph)
    val progress = GraphValidationProgress(parentContext.path)
    assertTrue(progress.covers(childContext.path))
    val staticChild = index.contextsFor(child).single { it.dynamicGraph == null }
    assertFalse(progress.covers(staticChild.path))
    assertEquals(
      listOf(childContext.path),
      index.extensionContextsOf(parentContext).map { it.path },
    )
    assertEquals(listOf("provideFake"), index.providerNames(childConsumer, childContext))

    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parentContext)
    assertEquals(listOf("ChildGraph", "AppGraph"), results.map { it.graph.name })
    assertTrue(results.all { it.requireCompleted().diagnostics.isEmpty() })
  }

  fun testDynamicMultibindingContributionsRemainAdditive() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Listener
        class RealListener : Listener
        class FakeListener : Listener

        @BindingContainer
        object RealBindings {
          @Provides @IntoSet fun realListener(): Listener = RealListener()
        }

        @BindingContainer
        object FakeBindings {
          @Provides @IntoSet fun fakeListener(): Listener = FakeListener()
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface AppGraph {
          @Multibinds(allowEmpty = true) fun listeners(): Set<Listener>
        }

        val dynamicGraph = createDynamicGraph<AppGraph>(FakeBindings)
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val context = index.contextsFor(graph).single { it.dynamicGraph != null }
    val queryContext = checkNotNull(index.queryContext(context))
    val contributionNames =
      index
        .bindingsInContext(queryContext)
        .filter { it.multibindingId != null }
        .mapNotNull { (it.pointer.element as? KtNamedDeclaration)?.name }
        .sorted()

    assertEquals(listOf("fakeListener", "realListener"), contributionNames)
    assertTrue(
      project
        .service<MetroGraphValidationService>()
        .validate(file, context)
        .requireCompleted()
        .diagnostics
        .isEmpty()
    )
  }

  fun testMultipleDynamicBindingsForOneKeyRemainDuplicates() {
    val file =
      myFixture.configureMetroFile(
        """
        @BindingContainer
        object RealBindings {
          @Provides fun realValue(): String = "real"
        }

        @BindingContainer
        object FirstFakeBindings {
          @Provides fun firstFakeValue(): String = "first"
        }

        @BindingContainer
        object SecondFakeBindings {
          @Provides fun secondFakeValue(): String = "second"
        }

        @DependencyGraph(bindingContainers = [RealBindings::class])
        interface AppGraph {
          val value: String
        }

        val dynamicGraph =
          createDynamicGraph<AppGraph>(FirstFakeBindings, SecondFakeBindings)
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val context = index.contextsFor(graph).single { it.dynamicGraph != null }
    val result =
      project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()

    assertEquals(listOf(MetroDiagnosticId.DUPLICATE_BINDING), result.diagnostics.map { it.id })
  }

  fun testAliasedIntrinsicIsRecognizedAndSameNamedFunctionIsIgnored() {
    val file =
      myFixture.configureMetroFile(
        """
        import dev.zacsweers.metro.createDynamicGraph as metroDynamicGraph

        @BindingContainer object FakeBindings
        @DependencyGraph interface AppGraph

        fun <T> createDynamicGraph(vararg ignored: Any): T = error("unused")

        val metro = metroDynamicGraph<AppGraph>(FakeBindings)
        val unrelated: AppGraph = createDynamicGraph(FakeBindings)
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val dynamicContexts = index.contextsFor(graph).filter { it.dynamicGraph != null }
    val metroCall =
      PsiTreeUtil.collectElementsOfType(file, KtCallExpression::class.java).single {
        it.calleeExpression?.text == "metroDynamicGraph"
      }

    assertEquals(1, dynamicContexts.size)
    assertSame(metroCall, dynamicContexts.single().contextPointer.element)
  }

  private fun BindingIndex.providerNames(
    consumer: ConsumerEntry,
    context: GraphContext,
  ): List<String> {
    val queryContext = checkNotNull(queryContext(context))
    return bindingsFor(consumer, queryContext)
      .filterIsInstance<KaBinding.Provided>()
      .mapNotNull { (it.pointer.element as? KtNamedDeclaration)?.name }
      .sorted()
  }
}
