// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.toolwindow.MetroTreeNode
import dev.zacsweers.metro.idea.toolwindow.MetroTreeStructure
import org.jetbrains.kotlin.psi.KtFile

/** Walks [MetroTreeStructure] directly, without Swing, and asserts the produced rows. */
class MetroToolWindowTreeTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    // Results are retained across index invalidation by design, so they survive across tests
    // sharing this project. Start each test clean.
    project.service<MetroGraphValidationService>().clearResults()
  }

  private var filter: String = ""

  private fun configure(): KtFile {
    return myFixture.configureMetroFile(
      """
      interface Service
      interface Analytics

      @Inject @SingleIn(AppScope::class) class ServiceImpl : Service

      interface ServiceBindings {
        @Binds fun bindService(impl: ServiceImpl): Service
      }

      @Inject @ContributesIntoSet(AppScope::class) class DebugAnalytics : Analytics
      @Inject @ContributesIntoSet(AppScope::class) class ProdAnalytics : Analytics

      interface UrlProviders {
        @Provides fun provideUrl(): String = "url"

        @Provides fun provideUnusedFlag(): Boolean = true
      }

      @Inject class Consumer(val service: Service, val analytics: Set<Analytics>, val url: String)

      @DependencyGraph(
        AppScope::class,
        bindingContainers = [ServiceBindings::class, UrlProviders::class],
      )
      interface AppGraph {
        val consumer: Consumer
      }
      """
    )
  }

  private fun structure(): MetroTreeStructure = MetroTreeStructure(project) { filter }

  private fun MetroTreeStructure.children(node: MetroTreeNode): List<MetroTreeNode> =
    computeChildren(node)

  fun testGraphAndCategoryRows() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode

    val graphs = structure.children(root)
    assertEquals(listOf("AppGraph"), graphs.map { it.text })

    val categories = structure.children(graphs.single())
    assertEquals(listOf("Scoped", "Unscoped", "Multibindings"), categories.map { it.text })

    val scoped = categories[0] as MetroTreeNode.Category
    assertEquals(listOf("ServiceImpl"), structure.children(scoped).map { it.text })

    // Contributed classes also provide their own types, so they appear here too
    val unscoped = categories[1] as MetroTreeNode.Category
    val unscopedRows = structure.children(unscoped)
    assertEquals(
      listOf(
        "Boolean",
        "Consumer",
        "DebugAnalytics",
        "ProdAnalytics",
        "Service -> ServiceImpl",
        "String",
      ),
      unscopedRows.map { it.text },
    )
    // Rows carry grayed locations for context
    assertTrue(unscopedRows.all { it.grayText?.startsWith("Test.kt:") == true })

    val multibindings = categories[2] as MetroTreeNode.Category
    val aggregate = structure.children(multibindings).single() as MetroTreeNode.Aggregate
    assertEquals("test.Analytics", aggregate.text)
    // The aggregate row names the key, so contributions show just their sources
    assertEquals(
      listOf("DebugAnalytics", "ProdAnalytics"),
      structure.children(aggregate).map { it.text },
    )
  }

  fun testFilterNarrowsRows() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single()

    filter = "String"
    val categories = structure.children(graph)
    assertEquals(listOf("Unscoped"), categories.map { it.text })
    assertEquals(
      listOf("String"),
      structure.children(categories.single()).map { it.text },
    )
  }

  fun testValidationNodeAppearsAfterValidating() {
    val file = configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphNode = structure.children(root).single() as MetroTreeNode.Graph

    // No validation node before a run
    assertTrue(structure.children(graphNode).none { it is MetroTreeNode.Validation })

    project.service<MetroGraphValidationService>().validate(file, graphNode.graph)

    val validation =
      structure.children(graphNode).filterIsInstance<MetroTreeNode.Validation>().single()
    val children = structure.children(validation)
    val summary = children.first() as MetroTreeNode.Summary
    assertTrue(summary.text, summary.text.endsWith(" bindings"))
    assertTrue(children.none { it is MetroTreeNode.Diagnostic })

    // With usage known, authored bindings nothing requested get their own category
    val unusedCategory =
      structure.children(graphNode).filterIsInstance<MetroTreeNode.Category>().single {
        it.text == "Unused"
      }
    assertEquals(listOf("Boolean"), structure.children(unusedCategory).map { it.text })
  }

  fun testDiagnosticRowsWithNavigableStacks() {
    val file =
      myFixture.configureMetroFile(
        """
        interface MissingThing

        @DependencyGraph
        interface AppGraph {
          val missing: MissingThing
        }
        """
      )
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphNode = structure.children(root).single() as MetroTreeNode.Graph
    project.service<MetroGraphValidationService>().validate(file, graphNode.graph)

    val validation =
      structure.children(graphNode).filterIsInstance<MetroTreeNode.Validation>().single()
    val diagnostic =
      structure.children(validation).filterIsInstance<MetroTreeNode.Diagnostic>().single()
    assertTrue(diagnostic.text, diagnostic.text.startsWith("[Metro/MissingBinding]"))

    val stackEntry = structure.children(diagnostic).single() as MetroTreeNode.StackEntry
    assertTrue(stackEntry.text, "is requested at" in stackEntry.text)
    assertNotNull(stackEntry.pointer?.element)
  }
}
