// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

/**
 * Checks class lookup for a qualified `@Inject` class or `@AssistedFactory`. The compiler resolves
 * a request that uses the class's qualifier and a request with no qualifier. A scoped class is one
 * instance for both keys.
 */
class MetroQualifiedInjectClassTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  private fun validate(
    source: String,
    graphName: String = "AppGraph",
  ): KaGraphValidationResult.Completed {
    return validate(myFixture.configureMetroFile(source), graphName)
  }

  /** Seals [graphName] against [file]'s current index. */
  private fun validate(
    file: KtFile,
    graphName: String = "AppGraph",
  ): KaGraphValidationResult.Completed {
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == graphName }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
  }

  /** Seals [graphName] and its extensions. Returns each result by its graph's name. */
  private fun validateWithExtensions(
    source: String,
    graphName: String = "AppGraph",
  ): Map<String, KaGraphValidationResult.Completed> {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == graphName }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, graph).map {
        it.requireCompleted()
      }
    return results.associateBy { checkNotNull(it.graph.name) }
  }

  private fun KaGraphValidationResult.Completed.assertNoDiagnostics() {
    assertTrue(diagnostics.joinToString { it.render() }, diagnostics.isEmpty())
  }

  /** The sealed bindings by short key, like `@Chosen Engine`. */
  private fun KaGraphValidationResult.Completed.bindingsByKey(): Map<String, KaBinding> {
    return bindings.asMap().mapKeys { (key, _) -> key.render(short = true) }
  }

  private fun KaGraphValidationResult.Completed.binding(key: String): KaBinding {
    val bindings = bindingsByKey()
    return checkNotNull(bindings[key]) { "No binding for $key in ${bindings.keys}" }
  }

  /** Asserts that [aliasKey] reads the binding at [targetKey] and has no scope of its own. */
  private fun KaGraphValidationResult.Completed.assertAliasOf(aliasKey: String, targetKey: String) {
    val alias = binding(aliasKey)
    assertEquals("synthetic alias", alias.label)
    assertNull(alias.scope)
    assertEquals(listOf(targetKey), alias.dependencies.map { it.typeKey.render(short = true) })
  }

  fun testUnscopedClassResolvesWithAndWithoutItsQualifier() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @Inject class Engine

        @DependencyGraph
        interface AppGraph {
          val engine: Engine

          @Chosen val chosenEngine: Engine
        }
        """,
      )

    result.assertNoDiagnostics()
    // The class has no scope, so each key has its own binding.
    assertTrue(result.binding("Engine") is KaBinding.ConstructorInjected)
    assertTrue(result.binding("@Chosen Engine") is KaBinding.ConstructorInjected)
  }

  fun testRequestWithNoQualifierResolvesAQualifiedClassOnItsOwn() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @Inject class Engine

        @DependencyGraph
        interface AppGraph {
          val engine: Engine
        }
        """,
      )

    result.assertNoDiagnostics()
    assertTrue(result.binding("Engine") is KaBinding.ConstructorInjected)
    assertFalse("@Chosen Engine" in result.bindingsByKey())
  }

  fun testConstructorParameterWithNoQualifierResolvesAQualifiedClass() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @Inject class Engine

        @Inject class Car(val engine: Engine)

        @DependencyGraph
        interface AppGraph {
          val car: Car
        }
        """,
      )

    result.assertNoDiagnostics()
    assertTrue(result.binding("Engine") is KaBinding.ConstructorInjected)
  }

  fun testRequestWithADifferentQualifierFindsNoClassBinding() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen
        @Qualifier annotation class Other

        @Chosen @Inject class Engine

        @Inject class Wheel

        @DependencyGraph
        interface AppGraph {
          @Other val engine: Engine

          @Chosen val wheel: Wheel
        }
        """,
      )

    assertEquals(
      listOf(MetroDiagnosticId.MISSING_BINDING, MetroDiagnosticId.MISSING_BINDING),
      result.diagnostics.map { it.id },
    )
    val rendered = result.diagnostics.joinToString("\n") { it.render() }
    assertTrue(rendered, "No binding found for @Other Engine" in rendered)
    assertTrue(rendered, "No binding found for @Chosen Wheel" in rendered)
  }

  fun testGenericClassResolvesWithAndWithoutItsQualifier() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @Inject class Box<T>

        @DependencyGraph
        interface AppGraph {
          val box: Box<String>

          @Chosen val chosenBox: Box<String>
        }
        """,
      )

    result.assertNoDiagnostics()
    assertTrue(result.binding("Box<String>") is KaBinding.ConstructorInjected)
    assertTrue(result.binding("@Chosen Box<String>") is KaBinding.ConstructorInjected)
  }

  fun testLibraryClassResolvesWithAndWithoutItsQualifier() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          """
          import libtest.LibEndpoint
          import libtest.LibQualifiedClient

          @DependencyGraph
          interface AppGraph {
            val client: LibQualifiedClient

            @LibEndpoint("primary") val primaryClient: LibQualifiedClient
          }
          """,
        )

      result.assertNoDiagnostics()
      val clients =
        result.bindings.asMap().filterKeys { it.renderedType == "libtest.LibQualifiedClient" }
      assertEquals(setOf(true, false), clients.keys.map { it.qualifier == null }.toSet())
      assertTrue(clients.values.all { it is KaBinding.ConstructorInjected })
    }
  }

  fun testScopedClassIsOneInstanceWhenItsKeyWithNoQualifierComesFirst() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @SingleIn(AppScope::class) @Inject class Engine

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val engine: Engine

          @Chosen val chosenEngine: Engine
        }
        """,
      )

    result.assertNoDiagnostics()
    // The first key keeps the scoped binding. The second key reads it.
    val engine = result.binding("Engine")
    assertTrue(engine is KaBinding.ConstructorInjected)
    assertNotNull(engine.scope)
    result.assertAliasOf("@Chosen Engine", "Engine")
  }

  fun testScopedClassIsOneInstanceWhenItsQualifiedKeyComesFirst() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @Chosen @SingleIn(AppScope::class) @Inject class Engine

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          @Chosen val chosenEngine: Engine

          val engine: Engine
        }
        """,
      )

    result.assertNoDiagnostics()
    val chosenEngine = result.binding("@Chosen Engine")
    assertTrue(chosenEngine is KaBinding.ConstructorInjected)
    assertNotNull(chosenEngine.scope)
    result.assertAliasOf("Engine", "@Chosen Engine")
  }

  fun testParentScopedClassIsOneInstanceAcrossExtensions() {
    val results =
      validateWithExtensions(
        """
        @Qualifier annotation class Chosen

        @Chosen @SingleIn(AppScope::class) @Inject class Engine

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val unqualifiedChild: UnqualifiedChild

          val qualifiedChild: QualifiedChild

          val bothChild: BothChild
        }

        @GraphExtension
        interface UnqualifiedChild {
          val engine: Engine
        }

        @GraphExtension
        interface QualifiedChild {
          @Chosen val engine: Engine
        }

        @GraphExtension
        interface BothChild {
          @Chosen val chosenEngine: Engine

          val engine: Engine
        }
        """,
      )

    assertEquals(
      setOf("AppGraph", "UnqualifiedChild", "QualifiedChild", "BothChild"),
      results.keys,
    )
    for (result in results.values) {
      result.assertNoDiagnostics()
    }

    // Each child reads its first key from the parent.
    val unqualifiedChild = results.getValue("UnqualifiedChild")
    assertTrue(unqualifiedChild.binding("Engine") is KaBinding.GraphDependency)
    val qualifiedChild = results.getValue("QualifiedChild")
    assertTrue(qualifiedChild.binding("@Chosen Engine") is KaBinding.GraphDependency)
    val bothChild = results.getValue("BothChild")
    assertTrue(bothChild.binding("@Chosen Engine") is KaBinding.GraphDependency)
    bothChild.assertAliasOf("Engine", "@Chosen Engine")

    // The parent holds one scoped binding. Its other key reads that binding.
    val parent = results.getValue("AppGraph")
    val engineBindings = listOf(parent.binding("Engine"), parent.binding("@Chosen Engine"))
    val scopedBinding = engineBindings.filterIsInstance<KaBinding.ConstructorInjected>().single()
    assertNotNull(scopedBinding.scope)
    val alias = engineBindings.filterIsInstance<KaBinding.Alias>().single()
    assertNull(alias.scope)
    assertEquals(listOf(scopedBinding.typeKey), alias.dependencies.map { it.typeKey })
  }

  fun testAssistedFactoryResolvesWithAndWithoutItsQualifier() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen

        @AssistedInject
        class Taco(@Assisted val seasoning: String)

        @Chosen
        @AssistedFactory
        fun interface TacoFactory {
          fun create(seasoning: String): Taco
        }

        @DependencyGraph
        interface AppGraph {
          val factory: TacoFactory

          @Chosen val chosenFactory: TacoFactory
        }
        """,
      )

    result.assertNoDiagnostics()
    assertTrue(result.binding("TacoFactory") is KaBinding.AssistedFactory)
    assertTrue(result.binding("@Chosen TacoFactory") is KaBinding.AssistedFactory)
  }

  fun testAssistedFactoryRequestWithADifferentQualifierFindsNoBinding() {
    val result =
      validate(
        """
        @Qualifier annotation class Chosen
        @Qualifier annotation class Other

        @AssistedInject
        class Taco(@Assisted val seasoning: String)

        @Chosen
        @AssistedFactory
        fun interface TacoFactory {
          fun create(seasoning: String): Taco
        }

        @DependencyGraph
        interface AppGraph {
          @Other val factory: TacoFactory
        }
        """,
      )

    assertEquals(listOf(MetroDiagnosticId.MISSING_BINDING), result.diagnostics.map { it.id })
  }

  fun testChangingTheClassQualifierKeepsOneBindingForEachKey() {
    val qualifiedSource =
      """
      package test

      import dev.zacsweers.metro.*

      @Qualifier annotation class Chosen

      @Chosen @Inject class Engine
      """
        .trimIndent()
    val engineFile = myFixture.addFileToProject("test/Engine.kt", qualifiedSource) as KtFile
    val graphFile =
      myFixture.configureMetroFile(
        """
        @DependencyGraph
        interface AppGraph {
          val engine: Engine
        }
        """,
      )
    val service = project.service<MetroResolutionService>()
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(engineFile))

    fun engineKeys(): List<String> {
      return service
        .awaitIndex(graphFile)
        .bindings
        .filter { it.typeKey.renderedType == "test.Engine" }
        .map { it.typeKey.render(short = true) }
        .sorted()
    }

    fun setEngineSource(source: String) {
      WriteCommandAction.runWriteCommandAction(project) { document.setText(source) }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    assertEquals(listOf("@Chosen Engine", "Engine"), engineKeys())
    validate(graphFile).assertNoDiagnostics()

    // Without its qualifier, the class has one key.
    setEngineSource(qualifiedSource.replace("@Chosen @Inject", "@Inject"))
    assertEquals(listOf("Engine"), engineKeys())
    validate(graphFile).assertNoDiagnostics()

    setEngineSource(qualifiedSource)
    assertEquals(listOf("@Chosen Engine", "Engine"), engineKeys())
    validate(graphFile).assertNoDiagnostics()
  }

  fun testEditorResolvesBothAccessorsToTheClass() {
    val file =
      myFixture.configureMetroFile(
        """
        @Qualifier annotation class Chosen

        @Chosen @Inject class Engine

        @DependencyGraph
        interface AppGraph {
          val engine: Engine

          @Chosen val chosenEngine: Engine
        }
        """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val queryContext = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
    val declarations = file.declarationsIncludingNested()
    val engineClass = declarations.filterIsInstance<KtClass>().single { it.name == "Engine" }

    for (name in listOf("engine", "chosenEngine")) {
      val consumer = checkNotNull(index.consumerEntryAt(declarations.property(name)))
      val binding = index.bindingsFor(consumer, queryContext).single()
      assertEquals(name, consumer.key, binding.typeKey)
      assertEquals(name, engineClass, binding.pointer.element)
    }
  }
}
