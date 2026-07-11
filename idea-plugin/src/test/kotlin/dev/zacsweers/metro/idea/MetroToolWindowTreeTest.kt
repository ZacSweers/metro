// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.components.service
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
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

    project.service<MetroGraphValidationService>().validate(file, graphNode.context)

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

  fun testDumbModeProducesNoChildren() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    assertTrue(structure.children(root).isNotEmpty())
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      assertTrue(structure.children(root).isEmpty())
    }
  }

  fun testRefreshedNodesReplaceStaleOnes() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single()

    val unscopedBefore =
      structure.children(graph).single { it.text == "Unscoped" } as MetroTreeNode.Category
    // Same content computes an equal node, which is what preserves tree expansion
    val unscopedAgain =
      structure.children(graph).single { it.text == "Unscoped" } as MetroTreeNode.Category
    assertEquals(unscopedBefore, unscopedAgain)

    // AsyncTreeModel keeps equal nodes and re-asks them for children, so a content change must
    // make the refreshed node unequal or the tree would serve stale rows
    filter = "String"
    val unscopedAfter =
      structure.children(graph).single { it.text == "Unscoped" } as MetroTreeNode.Category
    assertFalse(unscopedBefore == unscopedAfter)
    assertEquals(listOf("String"), structure.children(unscopedAfter).map { it.text })
  }

  fun testUnusedUnionsExtensionUsage() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Api

        @Inject class ChildThing(val api: Api)

        @GraphExtension
        interface ChildGraph {
          val childThing: ChildThing
        }

        @DependencyGraph
        interface AppGraph {
          val child: ChildGraph

          @Provides fun provideApi(): Api = object : Api {}
          @Provides fun provideUnused(): Int = 3
        }
        """
      )
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val appNode =
      structure.children(root).filterIsInstance<MetroTreeNode.Graph>().single {
        it.text == "AppGraph"
      }
    val service = project.service<MetroGraphValidationService>()
    service.validateWithExtensions(file, appNode.graph)

    // Api is consumed only by the child extension, so only the truly dead Int shows as unused
    val unused =
      structure.children(appNode).filterIsInstance<MetroTreeNode.Category>().single {
        it.text == "Unused"
      }
    assertEquals(listOf("Int"), structure.children(unused).map { it.text })
  }

  fun testMultiParentExtensionsHaveSeparateContextRows() {
    myFixture.configureMetroFile(
      """
      interface LeftOnly
      interface RightOnly

      @GraphExtension
      interface ChildGraph

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
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val childRows =
      structure.children(root).filterIsInstance<MetroTreeNode.Graph>().filter {
        it.text == "ChildGraph"
      }
    assertEquals(2, childRows.size)

    val rowsByParent = childRows.associateBy { it.context.chain[1].name }
    val left = rowsByParent.getValue("LeftParent")
    val right = rowsByParent.getValue("RightParent")
    assertTrue(left.grayText.orEmpty(), "via LeftParent" in left.grayText.orEmpty())
    assertTrue(right.grayText.orEmpty(), "via RightParent" in right.grayText.orEmpty())

    fun bindingRows(graph: MetroTreeNode.Graph): List<String> {
      val category = structure.children(graph).single() as MetroTreeNode.Category
      return structure.children(category).map { it.text }
    }

    assertEquals(listOf("LeftOnly"), bindingRows(left))
    assertEquals(listOf("RightOnly"), bindingRows(right))
  }

  fun testSameNamedQualifiersRenderAbbreviatedPackages() {
    myFixture.addFileToProject(
      "alpha/Tag.kt",
      "package alpha\n\nimport dev.zacsweers.metro.Qualifier\n\n@Qualifier annotation class Tag",
    )
    myFixture.addFileToProject(
      "beta/Tag.kt",
      "package beta\n\nimport dev.zacsweers.metro.Qualifier\n\n@Qualifier annotation class Tag",
    )
    myFixture.configureMetroFile(
      """
      interface TagProviders {
        @Provides @alpha.Tag fun alphaUrl(): String = "a"

        @Provides @beta.Tag fun betaUrl(): String = "b"
      }

      @DependencyGraph(bindingContainers = [TagProviders::class])
      interface AppGraph
      """
    )
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single()
    val unscoped =
      structure.children(graph).filterIsInstance<MetroTreeNode.Category>().single {
        it.text == "Unscoped"
      }
    assertEquals(
      listOf("@a.Tag String", "@b.Tag String"),
      structure.children(unscoped).map { it.text },
    )
  }

  fun testFilterRefreshThroughPlatformTreeModel() {
    configure()
    val treeStructure = structure()
    val treeModel = StructureTreeModel(treeStructure, testRootDisposable)
    val tree = Tree(AsyncTreeModel(treeModel, testRootDisposable))
    tree.isRootVisible = false

    fun visibleTexts(): List<String> {
      PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))
      return (0 until tree.rowCount).mapNotNull { row ->
        (TreeUtil.getLastUserObject(NodeDescriptor::class.java, tree.getPathForRow(row))?.element
            as? MetroTreeNode)
          ?.text
      }
    }

    assertTrue(visibleTexts().toString(), "Boolean" in visibleTexts())

    // The expanded tree must pick up the narrowed rows, not serve stale children
    filter = "String"
    PlatformTestUtil.waitForFuture(treeModel.invalidateAsync(), 30_000)
    val after = visibleTexts()
    assertTrue(after.toString(), "String" in after)
    assertTrue(after.toString(), "Boolean" !in after)
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
    project.service<MetroGraphValidationService>().validate(file, graphNode.context)

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
