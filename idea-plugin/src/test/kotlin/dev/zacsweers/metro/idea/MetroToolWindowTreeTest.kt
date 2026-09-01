// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.icons.AllIcons
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.TestOnlyThreading
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.eel.fs.EelFiles
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.WaitFor
import com.intellij.util.ui.tree.TreeUtil
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.GraphValidationProgress
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.IndexBuildPhase
import dev.zacsweers.metro.idea.index.IndexBuildProgress
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphContext
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.toolwindow.ExportGraphDebugInfoAction
import dev.zacsweers.metro.idea.toolwindow.GraphContextSelectorAction
import dev.zacsweers.metro.idea.toolwindow.IndexBuildStatusPanel
import dev.zacsweers.metro.idea.toolwindow.LoadOrRefreshGraphsAction
import dev.zacsweers.metro.idea.toolwindow.MetroGraphDebugExporter
import dev.zacsweers.metro.idea.toolwindow.MetroToolWindowPanel
import dev.zacsweers.metro.idea.toolwindow.MetroTreeNode
import dev.zacsweers.metro.idea.toolwindow.MetroTreeStructure
import dev.zacsweers.metro.idea.toolwindow.MetroValidationRequestService
import dev.zacsweers.metro.idea.toolwindow.MetroValidationResultPanel
import dev.zacsweers.metro.idea.toolwindow.MetroValidationResultTreeStructure
import dev.zacsweers.metro.idea.toolwindow.ValidateMetroGraphAction
import dev.zacsweers.metro.idea.toolwindow.ValidateSelectedGraphAction
import dev.zacsweers.metro.idea.toolwindow.ValidationStatusPanel
import dev.zacsweers.metro.idea.toolwindow.writeGraphDebugReport
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** Checks graph-browser and explicit-result rows, including platform tree refresh and selection. */
class MetroToolWindowTreeTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroResolutionService>().resetGraphBrowserActivation()
    // Results are retained across index invalidation by design, so they survive across tests
    // sharing this project. Start each test clean.
    project.service<MetroGraphValidationService>().clearResults()
    project.service<GraphContextPinService>().clear()
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

  /** Direct tree assertions start after the asynchronous index has been published. */
  private fun structure(): MetroTreeStructure {
    project.service<MetroResolutionService>().awaitIndex(module)
    return MetroTreeStructure(project) { filter }
  }

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
    val multibinding = structure.children(multibindings).single() as MetroTreeNode.Multibinding
    assertEquals("test.Analytics", multibinding.text)
    // The multibinding row names the key, so contributions show just their sources
    assertEquals(
      listOf("DebugAnalytics", "ProdAnalytics"),
      structure.children(multibinding).map { it.text },
    )
  }

  fun testDynamicGraphRowsIdentifyAndNavigateToTheirCallSite() {
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

      val graph = createDynamicGraph<AppGraph>(FakeBindings)
      """,
      fileName = "DynamicGraph.kt",
    )

    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphRows = structure.children(root).filterIsInstance<MetroTreeNode.Graph>()
    val staticRow = graphRows.single { it.context.dynamicGraph == null }
    val dynamicRow = graphRows.single { it.context.dynamicGraph != null }

    assertEquals("DynamicGraph.kt", staticRow.grayText)
    assertTrue(dynamicRow.grayText, dynamicRow.grayText!!.startsWith("dynamic at DynamicGraph.kt:"))
    assertTrue(dynamicRow.grayText, "FakeBindings" in dynamicRow.grayText)
    assertTrue(dynamicRow.pointer?.element is KtCallExpression)

    val unscoped =
      structure.children(dynamicRow).filterIsInstance<MetroTreeNode.Category>().single {
        it.text == "Unscoped"
      }
    assertEquals(
      listOf("FakeBindings", "String"),
      structure.children(unscoped).map { it.text },
    )

    project.service<GraphContextPinService>().pin(dynamicRow.context.path)
    val pinnedRows = structure.children(root).filterIsInstance<MetroTreeNode.Graph>()
    assertEquals(listOf(dynamicRow.context.path), pinnedRows.map { it.context.path })
  }

  fun testPinnedParentContextFocusesItsExtensionPath() {
    myFixture.configureMetroFile(
      """
      @GraphExtension
      interface ChildGraph

      @DependencyGraph
      interface LeftParent {
        val child: ChildGraph
      }

      @DependencyGraph
      interface RightParent {
        val child: ChildGraph
      }
      """
    )

    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val allRows = structure.children(root).filterIsInstance<MetroTreeNode.Graph>()
    assertEquals(4, allRows.size)

    val leftParent = allRows.single { it.graph.name == "LeftParent" }.context
    project.service<GraphContextPinService>().pin(leftParent.path)
    val leftRows = structure.children(root).filterIsInstance<MetroTreeNode.Graph>()
    assertEquals(listOf("ChildGraph", "LeftParent"), leftRows.map { it.graph.name })
    assertEquals(
      "LeftParent",
      leftRows.single { it.graph.name == "ChildGraph" }.context.rootGraph.name,
    )

    val rightParent = allRows.single { it.graph.name == "RightParent" }.context
    project.service<GraphContextPinService>().pin(rightParent.path)
    val rightRows = structure.children(root).filterIsInstance<MetroTreeNode.Graph>()
    assertEquals(listOf("ChildGraph", "RightParent"), rightRows.map { it.graph.name })
    assertEquals(
      "RightParent",
      rightRows.single { it.graph.name == "ChildGraph" }.context.rootGraph.name,
    )

    project.service<GraphContextPinService>().clear()
    assertEquals(4, structure.children(root).size)
  }

  fun testMissingPinnedContextFallsBackToAllGraphs() {
    val file = configure()
    val realIndex = project.service<MetroResolutionService>().awaitIndex(file)
    var currentIndex = realIndex
    val pinService = project.service<GraphContextPinService>()
    val structure =
      MetroTreeStructure(project, indexProvider = { currentIndex }, pinService = pinService) {
        filter
      }
    val root = structure.rootElement as MetroTreeNode
    val context = realIndex.contextsFor(realIndex.graphs.single()).single()
    pinService.pin(context.path)
    assertEquals(
      listOf(context.path),
      structure.children(root).map { (it as MetroTreeNode.Graph).context.path },
    )

    currentIndex = BindingIndex.EMPTY
    assertTrue(structure.children(root).isEmpty())
    assertEquals(context.path, pinService.pinnedPath)

    // Clear the pin only after a completed index confirms that its graph disappeared.
    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val graphNameStart = document.text.indexOf("interface AppGraph") + "interface ".length
    WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(graphNameStart, graphNameStart + "AppGraph".length, "ReplacementGraph")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    currentIndex = project.service<MetroResolutionService>().awaitIndex(file)

    assertEquals(listOf("ReplacementGraph"), structure.children(root).map { it.text })
    assertNull(pinService.pinnedPath)
  }

  fun testGraphContextSelectorUsesPrecomputedSnapshotWithoutReadAccess() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val structure =
      MetroTreeStructure(project, indexProvider = { index }, pinService = project.service()) {
        filter
      }
    val root = structure.rootElement as MetroTreeNode
    structure.children(root)
    val application = ApplicationManager.getApplication()
    val action =
      GraphContextSelectorAction(project.service()) {
        assertFalse(application.isReadAccessAllowed)
        structure.contextOptions()
      }
    var contextActionName: String? = null

    TestOnlyThreading.releaseTheAcquiredWriteIntentLockThenExecuteActionAndTakeWriteIntentLockBack {
      assertFalse(application.isReadAccessAllowed)
      val group = action.createPopupActionGroup(JPanel()) { null }
      contextActionName = group.getChildren(null).last().templatePresentation.text
    }

    assertTrue(contextActionName, contextActionName!!.startsWith("AppGraph ("))
    assertTrue(contextActionName, contextActionName.endsWith(": ${file.name})"))
  }

  fun testGenericInheritedProvidersDoNotShowRawTypeParameters() {
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

    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphs = structure.children(root).associateBy { it.text }
    for ((graphName, expectedType) in listOf("StringGraph" to "String", "IntGraph" to "Int")) {
      val graph = checkNotNull(graphs[graphName])
      val unscoped =
        structure.children(graph).filterIsInstance<MetroTreeNode.Category>().single {
          it.text == "Unscoped"
        }
      assertEquals(listOf(expectedType), structure.children(unscoped).map { it.text })
      assertEquals("1", unscoped.grayText)
    }
  }

  fun testRepeatedSourceFactoryRequestsAppearOnceInTheTree() {
    myFixture.addFileToProject(
      "test/SharedFactory.kt",
      """
      package test

      import dev.zacsweers.metro.*

      @AssistedInject
      class Widget<T>(@Assisted val id: String, val value: T) {
        @AssistedFactory
        fun interface Factory<T> {
          fun create(id: String): Widget<T>
        }
      }
      """
        .trimIndent(),
    )
    repeat(4) { number ->
      myFixture.addFileToProject(
        "test/Consumer$number.kt",
        """
        package test

        import dev.zacsweers.metro.Inject

        @Inject class Consumer$number(val factory: Widget.Factory<Int>)
        """
          .trimIndent(),
      )
    }
    myFixture.configureMetroFile(
      """
      @DependencyGraph
      interface AppGraph {
        val factory: Widget.Factory<Int>
        val consumer: Consumer0

        @Provides fun provideInt(): Int = 1
      }
      """,
      fileName = "FactoryGraph.kt",
    )

    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single()
    val unscoped =
      structure.children(graph).filterIsInstance<MetroTreeNode.Category>().single {
        it.text == "Unscoped"
      }
    val factoryRows =
      structure.children(unscoped).filterIsInstance<MetroTreeNode.BindingRow>().filter {
        it.binding is KaBinding.AssistedFactory &&
          it.binding.typeKey.renderedType == "test.Widget.Factory<kotlin.Int>"
      }

    assertEquals(1, factoryRows.size)
    assertEquals(unscoped.bindings.size.toString(), unscoped.grayText)
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

  fun testValidationNodeIdentityIncludesResultAndStaleState() {
    val file = configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single() as MetroTreeNode.Graph
    val result = project.service<MetroGraphValidationService>().validate(file, graph.context)

    val current = MetroTreeNode.Validation(graph, result, stale = false)
    val currentAgain = MetroTreeNode.Validation(graph, result, stale = false)
    val stale = MetroTreeNode.Validation(graph, result, stale = true)
    val failed =
      MetroTreeNode.Validation(
        graph,
        KaGraphValidationResult.InternalError(graph.context, IllegalStateException()),
        stale = false,
      )

    assertEquals(current, currentAgain)
    assertFalse(current == stale)
    assertFalse(current == failed)
  }

  fun testGraphBrowsingAndValidationRemainAvailableWhenEditorNavigationIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.enableBindingResolution = false
    try {
      val file = configure()
      val structure = structure()
      val root = structure.rootElement as MetroTreeNode
      val graph = structure.children(root).single() as MetroTreeNode.Graph

      assertEquals("AppGraph", graph.text)
      project.service<MetroGraphValidationService>().validate(file, graph.context)
      assertTrue(structure.children(graph).any { it is MetroTreeNode.Validation })
    } finally {
      settings.enableBindingResolution = true
    }
  }

  fun testSummaryIdentityIncludesDisplayedText() {
    val parent = MetroTreeNode.Root()

    assertEquals(
      MetroTreeNode.Summary(parent, "3 bindings"),
      MetroTreeNode.Summary(parent, "3 bindings"),
    )
    assertFalse(
      MetroTreeNode.Summary(parent, "3 bindings") == MetroTreeNode.Summary(parent, "4 bindings")
    )
  }

  fun testInternalValidationErrorIsPresentedAsAPluginFailure() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphNode = structure.children(root).single() as MetroTreeNode.Graph
    val result = KaGraphValidationResult.InternalError(graphNode.context, IllegalStateException())
    val validation = MetroTreeNode.Validation(graphNode, result, stale = false)

    assertEquals("internal Metro plugin error", validation.grayText)
    assertSame(AllIcons.General.Error, validation.icon)
    assertEquals(
      listOf("Validation failed due to an internal Metro plugin error"),
      structure.children(validation).map { it.text },
    )
  }

  fun testIncompleteValidationIsPresentedAsAnAnalysisLimit() {
    configure()
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphNode = structure.children(root).single() as MetroTreeNode.Graph
    val reason = "test.Node.Factory reached the source specialization depth limit"
    val result = KaGraphValidationResult.Incomplete(graphNode.context, reason)
    val validation = MetroTreeNode.Validation(graphNode, result, stale = false)

    assertEquals("analysis incomplete: $reason", validation.grayText)
    assertSame(AllIcons.General.Warning, validation.icon)
    assertEquals(
      listOf("Validation incomplete: $reason"),
      structure.children(validation).map { it.text },
    )
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

  fun testIndexBuildStatusPanelShowsStagesAndCountedProgress() {
    val panel = IndexBuildStatusPanel()

    panel.show(IndexBuildProgress(IndexBuildPhase.ANALYZING_DECLARATIONS, 4, 10))
    assertTrue(panel.isVisible)
    assertEquals("Analyzing Metro declarations (4 of 10 files)", panel.messageLabel.text)
    assertFalse(panel.progressBar.isIndeterminate)
    assertEquals(4, panel.progressBar.value)
    assertEquals(10, panel.progressBar.maximum)

    panel.show(IndexBuildProgress(IndexBuildPhase.READING_DEPENDENCY_METADATA))
    assertTrue(panel.progressBar.isIndeterminate)
    assertEquals("Reading dependency metadata", panel.messageLabel.text)

    panel.showWaitingForIdeIndexing()
    assertTrue(panel.progressBar.isIndeterminate)
    assertEquals("Waiting for IDE indexing to finish", panel.messageLabel.text)

    panel.showNotLoaded()
    assertTrue(panel.isVisible)
    assertFalse(panel.progressBar.isVisible)
    assertEquals("Metro graphs have not been loaded", panel.messageLabel.text)

    panel.showRefreshRequired()
    assertTrue(panel.isVisible)
    assertFalse(panel.progressBar.isVisible)
    assertEquals(
      "Metro graph data may be stale. Click Refresh to update",
      panel.messageLabel.text,
    )

    panel.clear()
    assertFalse(panel.isVisible)
  }

  fun testValidationStatusPanelShowsPreparingAndCountedProgress() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val panel = ValidationStatusPanel()

    panel.show(GraphValidationProgress(context.path, graphName = "AppGraph"))
    assertTrue(panel.isVisible)
    assertTrue(panel.progressBar.isIndeterminate)
    assertEquals("Validating Metro graph AppGraph", panel.messageLabel.text)

    panel.show(GraphValidationProgress(context.path, "ChildGraph", completed = 1, total = 3))
    assertFalse(panel.progressBar.isIndeterminate)
    assertEquals(1, panel.progressBar.value)
    assertEquals(3, panel.progressBar.maximum)
    assertEquals(
      "Validating Metro graph ChildGraph (2 of 3 graphs)",
      panel.messageLabel.text,
    )

    panel.clear()
    assertFalse(panel.isVisible)
  }

  fun testValidateActionIsDisabledWhileTheSelectedGraphIsRunning() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    var selectedContext: GraphContext? = null
    var validationRunning = false
    var validatedContext: GraphContext? = null
    val action =
      ValidateSelectedGraphAction(
        selectedContext = { selectedContext },
        isValidationRunning = { validationRunning },
        validate = { validatedContext = it },
      )
    val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN) { null }

    action.update(event)
    assertFalse(event.presentation.isEnabled)

    selectedContext = context
    action.update(event)
    assertTrue(event.presentation.isEnabled)

    validationRunning = true
    action.update(event)
    assertFalse(event.presentation.isEnabled)
    action.actionPerformed(event)
    assertNull(validatedContext)

    validationRunning = false
    action.actionPerformed(event)
    assertSame(context, validatedContext)
  }

  fun testGraphBrowserActionLoadsOnceThenRefreshes() {
    configure()
    val service = project.service<MetroResolutionService>()
    var refreshes = 0
    val action = LoadOrRefreshGraphsAction(service) { refreshes++ }
    val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN) { null }

    action.update(event)
    assertEquals("Load", event.presentation.text)
    assertEquals("Load graphs and bindings", event.presentation.description)
    assertFalse(service.isGraphBrowserActivated)

    action.actionPerformed(event)
    action.update(event)
    assertEquals(1, refreshes)
    assertTrue(service.isGraphBrowserActivated)
    assertEquals("Refresh", event.presentation.text)
    assertEquals("Refresh graphs and bindings", event.presentation.description)
  }

  fun testToolWindowWaitsForInitialGraphLoad() {
    configure()
    val service = project.service<MetroResolutionService>()
    val panel = MetroToolWindowPanel(project)
    try {
      val status = toolWindowStatus(panel)
      assertFalse(service.isGraphBrowserActivated)
      assertTrue(status.isVisible)
      assertEquals("Metro graphs have not been loaded", status.messageLabel.text)
    } finally {
      Disposer.dispose(panel)
    }
  }

  fun testManualValidationShowsNewGraphResultsWithoutRefreshingBrowser() {
    assertManualValidationResult(rename = false)
  }

  fun testManualValidationShowsRenamedGraphResultsWithoutRefreshingBrowser() {
    assertManualValidationResult(rename = true)
  }

  /** A requested graph can be absent from the browser's retained generation. */
  private fun assertManualValidationResult(rename: Boolean) {
    val file =
      myFixture.configureMetroFile(
        """
        interface MissingThing

        @DependencyGraph interface AppGraph {
          val missing: MissingThing
        }
        """
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    val originalGraph = initial.graphs.single()
    val settings = MetroSettings.getInstance(project).state
    val previousAutomaticRefresh = settings.automaticallyRefreshGraphData
    settings.automaticallyRefreshGraphData = false
    service.settingsChanged()
    service.activateGraphBrowser()
    val panel = MetroToolWindowPanel(project)
    try {
      val browser = toolWindowTree(panel)
      treeNodes(browser)
      browser.setSelectionRow(0)
      val selectedGraph = selectedTreeNode(browser) as MetroTreeNode.Graph
      val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
      val requestedName = if (rename) "RenamedGraph" else "AddedGraph"
      WriteCommandAction.runWriteCommandAction(project) {
        if (rename) {
          val offset = document.text.indexOf("AppGraph")
          document.replaceString(offset, offset + "AppGraph".length, requestedName)
        } else {
          document.insertString(
            document.textLength,
            "\n@DependencyGraph interface AddedGraph { val missing: MissingThing }\n",
          )
        }
      }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
      panel.selectAndValidate(ClassId.topLevel(FqName("test.$requestedName")), file.virtualFile)

      val results = waitForValidationResults(panel)
      val rows = treeNodes(results.tree)
      val resultGraph = rows.filterIsInstance<MetroTreeNode.Graph>().single()
      assertEquals(requestedName, resultGraph.graph.name)
      val diagnostic = rows.filterIsInstance<MetroTreeNode.Diagnostic>().single()
      assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.diagnostic.id)
      val stack = rows.filterIsInstance<MetroTreeNode.StackEntry>()
      assertTrue(stack.isNotEmpty())
      assertTrue(stack.any { it.pointer?.element != null })
      val validation = rows.filterIsInstance<MetroTreeNode.Validation>().single()
      assertEquals("1 problem", validation.grayText)
      assertSame(initial, service.indexForToolWindow(module))
      assertTrue(service.isManualGraphDataRefreshRequired)
      assertEquals(
        listOf(originalGraph.declarationId),
        treeNodes(browser).filterIsInstance<MetroTreeNode.Graph>().map { it.graph.declarationId },
      )
      assertEquals(selectedGraph, selectedTreeNode(browser))

      val closeEvent =
        AnActionEvent.createFromAnAction(
          results.closeAction,
          null,
          ActionPlaces.UNKNOWN,
          DataContext { null },
        )
      results.closeAction.actionPerformed(closeEvent)
      assertFalse(results.isVisible)
      assertEquals(selectedGraph, selectedTreeNode(browser))
      assertSame(initial, service.indexForToolWindow(module))
    } finally {
      Disposer.dispose(panel)
      settings.automaticallyRefreshGraphData = previousAutomaticRefresh
      service.settingsChanged()
    }
  }

  fun testValidationResultViewShowsCleanGraphAndExtensions() {
    val file =
      myFixture.configureMetroFile(
        """
        @GraphExtension interface ChildGraph

        @DependencyGraph interface AppGraph {
          val child: ChildGraph
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val panel = MetroToolWindowPanel(project)
    try {
      panel.selectAndValidate(checkNotNull(graph.classId), file.virtualFile)
      val results = waitForValidationResults(panel)
      val rows = treeNodes(results.tree)
      val graphRows = rows.filterIsInstance<MetroTreeNode.Graph>()
      assertEquals(listOf("AppGraph", "ChildGraph"), graphRows.map { it.graph.name })
      assertTrue(graphRows.last().text, "via AppGraph" in graphRows.last().text)
      assertTrue(graphRows.all { it.grayText == "no problems found" })
      assertTrue(rows.none { it is MetroTreeNode.Diagnostic })
      assertEquals(2, rows.filterIsInstance<MetroTreeNode.Validation>().size)
      object : WaitFor(30_000) {
          override fun condition(): Boolean {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            return selectedTreeNode(toolWindowTree(panel)) is MetroTreeNode.Validation
          }
        }
        .assertCompleted("Validation should still select the browser's result node")
    } finally {
      Disposer.dispose(panel)
    }
  }

  fun testValidationResultRowsRemainAvailableDuringIdeIndexing() {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph interface IncompleteGraph
        @DependencyGraph interface ErroredGraph
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val contexts = index.graphs.associate { it.name to index.contextsFor(it).single() }
    val results = MetroValidationResultTreeStructure(project)
    results.showResults(
      listOf(
        KaGraphValidationResult.Incomplete(contexts.getValue("IncompleteGraph"), "analysis limit"),
        KaGraphValidationResult.InternalError(
          contexts.getValue("ErroredGraph"),
          IllegalStateException(),
        ),
      )
    )
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      val graphs =
        results.getChildElements(results.rootElement).filterIsInstance<MetroTreeNode.Graph>()
      assertEquals(2, graphs.size)
      val summaries = graphs.flatMap { graph ->
        val validation = results.getChildElements(graph).single()
        results.getChildElements(validation).map { (it as MetroTreeNode).text }
      }
      assertEquals(
        setOf(
          "Validation incomplete: analysis limit",
          "Validation failed due to an internal Metro plugin error",
        ),
        summaries.toSet(),
      )
    }
    results.showResults(emptyList())
    assertEquals(
      "No graph contexts were available for validation",
      (results.getChildElements(results.rootElement).single() as MetroTreeNode.Summary).text,
    )
  }

  fun testLateValidationCannotReplaceTheLatestRequestedResult() {
    assertLateValidationResultIgnored(disposePanel = false)
  }

  fun testDisposedPanelDoesNotShowACompletedValidationResult() {
    assertLateValidationResultIgnored(disposePanel = true)
  }

  fun testClosingValidationResultsDropsPendingNavigation() {
    assertOldResultNavigationIgnored(replaceResults = false)
  }

  fun testNewValidationResultsDropPendingNavigation() {
    assertOldResultNavigationIgnored(replaceResults = true)
  }

  /** Closing or replacing a visible result must invalidate navigation already resolving for it. */
  private fun assertOldResultNavigationIgnored(replaceResults: Boolean) {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph interface EarlierGraph
        @DependencyGraph interface LatestGraph
        """
      )
    project.service<MetroResolutionService>().awaitIndex(file)
    val panel = MetroToolWindowPanel(project)
    val service = project.service<MetroNavigationService>()
    val resolutionStarted = CompletableFuture<Unit>()
    val releaseResolution = CompletableDeferred<Unit>()
    val editors = FileEditorManager.getInstance(project)
    try {
      panel.selectAndValidate(ClassId.topLevel(FqName("test.EarlierGraph")), file.virtualFile)
      val results = waitForValidationResults(panel)
      treeNodes(results.tree)
      results.tree.setSelectionRow(0)
      editors.closeFile(file.virtualFile)
      assertFalse(editors.isFileOpen(file.virtualFile))
      service.setTargetResolutionObserver {
        resolutionStarted.complete(Unit)
        releaseResolution.await()
      }
      val navigation = checkNotNull(results.navigateSelected())
      val navigationFinished = CompletableFuture<Unit>()
      navigation.invokeOnCompletion { navigationFinished.complete(Unit) }
      PlatformTestUtil.waitForFuture(resolutionStarted, 30_000)

      if (replaceResults) {
        panel.selectAndValidate(ClassId.topLevel(FqName("test.LatestGraph")), file.virtualFile)
        waitForValidationResults(panel)
      } else {
        results.closeAction.actionPerformed(
          AnActionEvent.createFromAnAction(results.closeAction, null, ActionPlaces.UNKNOWN) { null }
        )
      }
      releaseResolution.complete(Unit)
      PlatformTestUtil.waitForFuture(navigationFinished, 30_000)
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertFalse(
        "The previous result must not reopen its source file",
        editors.isFileOpen(file.virtualFile),
      )

      if (replaceResults) {
        service.setTargetResolutionObserver(null)
        treeNodes(results.tree)
        results.tree.setSelectionRow(0)
        val currentNavigation = checkNotNull(results.navigateSelected())
        val currentNavigationFinished = CompletableFuture<Unit>()
        currentNavigation.invokeOnCompletion { currentNavigationFinished.complete(Unit) }
        PlatformTestUtil.waitForFuture(currentNavigationFinished, 30_000)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertTrue(
          "The current result should remain navigable",
          editors.isFileOpen(file.virtualFile),
        )
      }
    } finally {
      releaseResolution.complete(Unit)
      service.setTargetResolutionObserver(null)
      Disposer.dispose(panel)
    }
  }

  /** Different graph paths can finish out of order while the panel shows its latest request. */
  private fun assertLateValidationResultIgnored(disposePanel: Boolean) {
    val file =
      myFixture.configureMetroFile(
        """
        @DependencyGraph interface EarlierGraph
        @DependencyGraph interface LatestGraph
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val earlier = index.graphs.single { it.name == "EarlierGraph" }
    val latest = index.graphs.single { it.name == "LatestGraph" }
    val earlierPath = index.contextsFor(earlier).single().path
    val validationService = project.service<MetroGraphValidationService>()
    val publicationReady = CompletableFuture<Unit>()
    val releasePublication = CountDownLatch(1)
    val panel = MetroToolWindowPanel(project)
    validationService.setBeforeValidationPublicationObserver { path, _ ->
      if (path == earlierPath) {
        publicationReady.complete(Unit)
        releasePublication.await()
      }
    }
    try {
      panel.selectAndValidate(checkNotNull(earlier.classId), file.virtualFile)
      PlatformTestUtil.waitForFuture(publicationReady, 30_000)
      if (disposePanel) {
        Disposer.dispose(panel)
      } else {
        panel.selectAndValidate(checkNotNull(latest.classId), file.virtualFile)
        waitForValidationResults(panel)
      }
      releasePublication.countDown()
      object : WaitFor(30_000) {
          override fun condition(): Boolean {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            return !validationService.isValidationRunning(earlierPath)
          }
        }
        .assertCompleted("The earlier validation should finish")
      val results =
        com.intellij.util.ui.UIUtil.findComponentOfType(
          panel,
          MetroValidationResultPanel::class.java,
        )
      if (disposePanel) {
        assertNull(results)
      } else {
        val graphs = treeNodes(checkNotNull(results).tree).filterIsInstance<MetroTreeNode.Graph>()
        assertEquals(listOf("LatestGraph"), graphs.map { it.graph.name })
      }
    } finally {
      releasePublication.countDown()
      validationService.setBeforeValidationPublicationObserver(null)
      if (!Disposer.isDisposed(panel)) Disposer.dispose(panel)
    }
  }

  fun testToolWindowPanelRecoversAfterDumbMode() {
    val file = configure()
    project.service<MetroResolutionService>().awaitIndex(file)
    var panel: MetroToolWindowPanel? = null
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      panel = MetroToolWindowPanel(project)
      assertEquals(0, toolWindowTree(checkNotNull(panel)).rowCount)
      val status = toolWindowStatus(checkNotNull(panel))
      assertTrue(status.isVisible)
      assertEquals("Waiting for IDE indexing to finish", status.messageLabel.text)
    }

    val tree = toolWindowTree(checkNotNull(panel))
    object : WaitFor(30_000) {
        override fun condition(): Boolean {
          PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))
          return tree.rowCount > 0
        }
      }
      .assertCompleted("The Metro tree should populate when smart mode resumes")
    assertFalse(toolWindowStatus(checkNotNull(panel)).isVisible)
    com.intellij.openapi.util.Disposer.dispose(checkNotNull(panel))
  }

  fun testDisposedToolWindowPanelIgnoresValidationRequests() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val panel = MetroToolWindowPanel(project)
    Disposer.dispose(panel)

    panel.selectAndValidate(checkNotNull(graph.classId), file.virtualFile)

    assertNull(project.service<MetroGraphValidationService>().cachedResult(file, context))
  }

  fun testDisposedToolWindowOwnerDropsPendingNavigation() {
    val file = configure()
    val pointer =
      SmartPointerManager.getInstance(project)
        .createSmartPsiElementPointer(file.declarations.first())
    val panel = MetroToolWindowPanel(project)
    val service = project.service<MetroNavigationService>()
    val resolutionStarted = CompletableFuture<Unit>()
    val releaseResolution = CompletableDeferred<Unit>()
    val delivered = AtomicBoolean()
    service.setTargetResolutionObserver {
      resolutionStarted.complete(Unit)
      releaseResolution.await()
    }

    try {
      val job = checkNotNull(service.resolveTargets(panel, listOf(pointer)) { delivered.set(true) })
      PlatformTestUtil.waitForFuture(resolutionStarted, 30_000)
      Disposer.dispose(panel)
      releaseResolution.complete(Unit)

      object : WaitFor(30_000) {
          override fun condition(): Boolean {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            return job.isCompleted
          }
        }
        .assertCompleted("Disposed tool-window navigation should finish without delivery")

      assertFalse(delivered.get())
      assertNull(service.resolveTargets(panel, listOf(pointer)) { delivered.set(true) })
    } finally {
      releaseResolution.complete(Unit)
      service.setTargetResolutionObserver(null)
      if (!Disposer.isDisposed(panel)) Disposer.dispose(panel)
    }
  }

  fun testToolWindowRejectsValidationSupersededBeforeItsTargetResolves() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val requestService = project.service<MetroValidationRequestService>()
    val staleRequest = requestService.beginRequest()
    requestService.beginRequest()
    val panel = MetroToolWindowPanel(project)
    try {
      panel.selectAndValidate(checkNotNull(graph.classId), file.virtualFile, staleRequest)

      assertNull(project.service<MetroGraphValidationService>().cachedResult(file, context))
    } finally {
      Disposer.dispose(panel)
    }
  }

  fun testReplacementGraphLookupIgnoresCanceledLookupCompletion() {
    val file = configure()
    val graph = project.service<MetroResolutionService>().awaitIndex(file).graphs.single()
    val lookupFile = myFixture.addFileToProject("test/Lookup.kt", "package test")
    val callbacks = mutableListOf<(KaGraphDeclaration?) -> Unit>()
    val jobs = mutableListOf<Job>()
    val panel =
      MetroToolWindowPanel(project) { _, _, callback ->
        callbacks += callback
        Job().also(jobs::add)
      }
    try {
      panel.selectAndValidate(checkNotNull(graph.classId), lookupFile.virtualFile)
      object : WaitFor(30_000) {
          override fun condition(): Boolean {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            return callbacks.isNotEmpty()
          }
        }
        .assertCompleted("The first graph lookup should start")

      val canceledLookup = callbacks.lastIndex
      panel.retryPendingValidationForTest()
      val replacementLookup = callbacks.lastIndex

      assertEquals(canceledLookup + 1, replacementLookup)
      assertTrue(jobs[canceledLookup].isCancelled)
      callbacks[canceledLookup](null)
      assertTrue(panel.hasPendingValidationLookupForTest())

      callbacks[replacementLookup](null)
      assertFalse(panel.hasPendingValidationLookupForTest())
    } finally {
      Disposer.dispose(panel)
    }
  }

  fun testMissingRequestedGraphDoesNotValidateTheSelectedGraph() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val panel = MetroToolWindowPanel(project)
    try {
      val tree = toolWindowTree(panel)
      PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))
      tree.setSelectionRow(0)

      panel.selectAndValidate(ClassId.topLevel(FqName("test.MissingGraph")), file.virtualFile)
      PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))

      assertNull(project.service<MetroGraphValidationService>().cachedResult(file, context))
    } finally {
      Disposer.dispose(panel)
    }
  }

  fun testValidateGraphActionRecognizesAnImportedAnnotationAlias() {
    val file =
      myFixture.configureByText(
        "AliasedGraph.kt",
        """
        package test

        import dev.zacsweers.metro.DependencyGraph as MetroGraph

        @MetroGraph
        interface <caret>AppGraph
        """
          .trimIndent(),
      )
    val action = ValidateMetroGraphAction()
    val dataContext = DataContext { dataId ->
      when {
        CommonDataKeys.PROJECT.`is`(dataId) -> project
        CommonDataKeys.EDITOR.`is`(dataId) -> myFixture.editor
        CommonDataKeys.PSI_FILE.`is`(dataId) -> file
        else -> null
      }
    }
    val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, dataContext)

    action.update(event)

    assertTrue(event.presentation.isEnabledAndVisible)
  }

  fun testGraphDebugReportIsDeterministicOmitsPrivateValuesAndUsesRealSelection() {
    module.addKotlinStdlibLibrary()
    val outputRoot = Files.createTempDirectory("metro-debug-private")
    val reports = Files.createDirectory(outputRoot.resolve("reports-secret"))
    val sentinel = Files.writeString(reports.resolve("keep.txt"), "keep this report")
    val trace = outputRoot.resolve("trace-secret")
    try {
      project.setMetroOptions(
        "reports-destination" to reports.toString(),
        "trace-destination" to trace.toString(),
        "compiler-version" to "2.3.20",
        "compiler-version-aliases" to "private-version-from=private-version-to",
      )
      val file =
        myFixture.configureMetroFile(
          """
          @Qualifier annotation class SecretTag(val value: String)
          @Target(AnnotationTarget.TYPE) annotation class TypeSecret(val value: String)

          @Inject class Service
          interface MapService

          @BindingContainer
          interface UnwiredProviders {
            @Provides fun unwired(): Service = Service()
          }

          @DependencyGraph
          interface AppGraph {
            val service: Service
            @SecretTag("qualifier-secret") val secret: String
            val typed: @TypeSecret("type-use-secret") Long
            val services: Map<String, MapService>

            @Provides fun preferred(): Service = Service()
            @Provides @SecretTag("qualifier-secret")
            fun secretValue(): String = "provider-body-secret"
            @Provides @SecretTag("other-qualifier-secret")
            fun otherSecretValue(): String = "other-provider-body-secret"
            @Provides fun typedValue(): @TypeSecret("type-use-secret") Long = 1L
            @Provides @IntoMap @StringKey("map-key-secret")
            fun mapService(): MapService = object : MapService {}
          }
          """
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val context = index.contextsFor(graph).single()
      val exporter = project.service<MetroGraphDebugExporter>()
      val report = checkNotNull(exporter.report(context))

      assertEquals(report, exporter.report(context))
      assertTrue(report, "formatVersion=1" in report)
      assertTrue(report, "plugin.version=$VERSION" in report)
      assertTrue(report, "plugin.gitSha=" in report)
      assertTrue(report, "\"compilerVersion\": \"2.3.20\"" in report)
      assertTrue(report, "\"compilerVersionAliases\": \"<redacted>\"" in report)
      assertTrue(report, "\"reportsEnabled\": true" in report)
      assertTrue(report, "\"traceEnabled\": true" in report)
      val privateValues =
        listOfNotNull(
          reports.toString(),
          trace.toString(),
          file.virtualFile.path.takeIf { path -> path.count { it == '/' } > 1 },
          System.getProperty("user.home")?.takeIf { it.isNotEmpty() },
          "private-version-from",
          "private-version-to",
          "qualifier-secret",
          "type-use-secret",
          "provider-body-secret",
          "map-key-secret",
        )
      for (privateValue in privateValues) {
        assertFalse("Report leaked $privateValue", privateValue in report)
      }
      assertEquals("keep this report", EelFiles.readString(sentinel))
      assertFalse("Reading options must not initialize traceDir", Files.exists(trace))

      val serviceRequest = debugRequest(report, "test.Service")
      val raw = debugBindingReferences(serviceRequest, "rawSameType")
      val inContext = debugBindingReferences(serviceRequest, "inContext")
      val selected = debugBindingReferences(serviceRequest, "selected")
      assertEquals(3, raw.size)
      assertEquals(2, inContext.size)
      assertEquals(1, selected.size)
      assertTrue(raw.containsAll(inContext))
      assertTrue(inContext.containsAll(selected))
      val chosen = debugBindingRecord(report, selected.single())
      assertTrue(chosen, "  kind=Provided" in chosen)
      assertTrue(chosen, " preferred" in chosen)
      val absent = raw.single { it !in inContext }
      assertTrue(
        debugBindingRecord(report, absent),
        " unwired" in debugBindingRecord(report, absent),
      )

      val mapRequest = debugRequest(report, "Map<kotlin.String")
      val selectedMap = debugBindingReferences(mapRequest, "selected")
      assertEquals("Only the aggregate collection satisfies the map request", 1, selectedMap.size)
      val collection = debugBindingRecord(report, selectedMap.single())
      assertTrue(collection, "  kind=Multibinding" in collection)
      val elements = debugBindingReferences(collection, "sourceBindings")
      assertEquals(1, elements.size)
      assertFalse(elements.single() in selectedMap)
      val mapElement = debugBindingRecord(report, elements.single())
      assertTrue(mapElement, "  kind=Provided" in mapElement)
      assertTrue(mapElement, "  indexed=false" in mapElement)
      assertTrue(mapElement, " mapService" in mapElement)
      assertTrue(mapElement, "@dev.zacsweers.metro.internal.MultibindingElement(" in mapElement)
      assertTrue(mapElement, "  mapKey=mapKey#" in mapElement)

      val qualifierIds =
        Regex("@test\\.SecretTag\\(value=<redacted>\\) \\[annotation#([0-9]+)]")
          .findAll(report)
          .map { it.groupValues[1] }
          .toSet()
      assertEquals(
        "Distinct qualifiers must stay distinguishable without their values",
        2,
        qualifierIds.size,
      )
      assertNull(project.service<MetroGraphValidationService>().cachedResult(file, context))
    } finally {
      FileUtil.delete(outputRoot.toFile())
    }
  }

  fun testGraphDebugReportIsWrittenToUniqueFiles() {
    val outputRoot = Files.createTempDirectory("metro-debug-reports")
    try {
      val first = writeGraphDebugReport(outputRoot, "first report")
      val second = writeGraphDebugReport(outputRoot, "second report")

      assertEquals(outputRoot, first.parent)
      assertTrue(first.fileName.toString().startsWith("metro-graph-debug-"))
      assertTrue(first.fileName.toString().endsWith(".txt"))
      assertFalse(first == second)
      assertEquals("first report", EelFiles.readString(first))
      assertEquals("second report", EelFiles.readString(second))
    } finally {
      FileUtil.delete(outputRoot.toFile())
    }
  }

  fun testGraphDebugReportRetainsTheSelectedExtensionPath() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Service

        @GraphExtension
        interface ChildGraph {
          val service: Service
        }

        @DependencyGraph
        interface LeftParent {
          val child: ChildGraph
          @Provides fun leftService(): Service = object : Service {}
        }

        @DependencyGraph
        interface RightParent {
          val child: ChildGraph
          @Provides fun rightService(): Service = object : Service {}
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val child = index.graphs.single { it.name == "ChildGraph" }
    val contexts = index.contextsFor(child).associateBy { it.rootGraph.name }
    val exporter = project.service<MetroGraphDebugExporter>()
    for ((parent, provider) in
      listOf("LeftParent" to "leftService", "RightParent" to "rightService")) {
      val report = checkNotNull(exporter.report(contexts.getValue(parent)))
      val path = report.lineSequence().single { it.startsWith("path (selected graph first)=") }
      assertTrue(path, "test.ChildGraph" in path)
      assertTrue(path, "test.$parent" in path)
      val selected =
        debugBindingReferences(debugRequest(report, "test.Service"), "selected").single()
      assertTrue(
        debugBindingRecord(report, selected),
        " $provider" in debugBindingRecord(report, selected),
      )
    }
  }

  fun testGraphDebugReportUsesInitializedSyntheticGraphBindings() {
    val file =
      myFixture.configureMetroFile(
        """
        @ContributesTo(AppScope::class)
        interface FactoryContract

        @GraphExtension
        interface ChildGraph {
          @GraphExtension.Factory
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(AppScope::class)
        interface AppGraph {
          val contract: FactoryContract
          val childFactory: ChildGraph.Factory
        }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val report =
      checkNotNull(
        project.service<MetroGraphDebugExporter>().report(index.contextsFor(graph).single())
      )
    val writtenSupertypes =
      report.lineSequence().single { it.startsWith("  writtenSupertypeKeys=") }
    val selectedSupertypes =
      report.lineSequence().single { it.startsWith("  selectedSupertypeKeys=") }
    assertFalse(writtenSupertypes, "test.FactoryContract" in writtenSupertypes)
    assertTrue(selectedSupertypes, "test.FactoryContract" in selectedSupertypes)

    val aliasId =
      debugBindingReferences(debugRequest(report, "test.FactoryContract"), "selected").single()
    val alias = debugBindingRecord(report, aliasId)
    assertTrue(alias, "  kind=Alias" in alias)
    assertTrue(alias, "  consumedKey=test.AppGraph [type#" in alias)

    val factoryId =
      debugBindingReferences(debugRequest(report, "test.ChildGraph.Factory"), "selected").single()
    val factory = debugBindingRecord(report, factoryId)
    assertTrue(factory, "  kind=GraphExtension" in factory)
    assertTrue(factory, "  isFactory=true" in factory)
    assertTrue(factory, "  ownerKey=test.AppGraph [type#" in factory)
  }

  fun testGraphDebugReportShowsCurrentAndStaleValidationWithoutResealing() {
    val file =
      myFixture.configureMetroFile(
        """
        @Inject class Service
        @DependencyGraph interface AppGraph { val service: Service }
        """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    val exporter = project.service<MetroGraphDebugExporter>()
    val validation = project.service<MetroGraphValidationService>()
    val unvalidated = checkNotNull(exporter.report(context))
    assertTrue(unvalidated, "state=never validated" in unvalidated)

    val result = validation.validate(file, context)
    val current = checkNotNull(exporter.report(context))
    assertTrue(current, "freshness=current" in current)
    assertTrue(current, "state=completed" in current)

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val insertion = document.text.indexOf("val service: Service")
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(insertion, "val missing: Long; ")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    project.service<MetroResolutionService>().awaitIndex(file)

    val stale = checkNotNull(exporter.report(context))
    assertTrue(stale, "freshness=stale" in stale)
    assertTrue(stale, "state=completed" in stale)
    assertTrue(stale, "graphRequests=2" in stale)
    assertSame(result, checkNotNull(validation.cachedResult(file, context)).result)
  }

  fun testExportGraphDebugInfoActionRequiresAnExactGraphSelection() {
    val file = configure()
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    var selected: GraphContext? = null
    val action = ExportGraphDebugInfoAction(project) { selected }
    val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN) { null }

    action.update(event)
    assertFalse(event.presentation.isEnabled)
    selected = context
    action.update(event)
    assertTrue(event.presentation.isEnabled)
    assertEquals("Export Graph Debug Info", event.presentation.text)
  }

  private fun debugRequest(report: String, type: String): String {
    return report
      .substringAfter("[Graph requests]\n")
      .substringBefore("\n[Candidate bindings]")
      .split(Regex("(?m)^request [0-9]+:\\n"))
      .single { "  key=$type [type#" in it }
  }

  private fun debugBindingReferences(request: String, name: String): List<String> {
    val line = request.lineSequence().single { it.startsWith("  $name=") }
    return Regex("binding#[0-9]+").findAll(line).map { it.value }.toList()
  }

  private fun debugBindingRecord(report: String, reference: String): String {
    return report
      .substringAfter("[Candidate bindings]\n")
      .substringAfter("$reference:\n")
      .split(Regex("(?m)^binding#[0-9]+:"), limit = 2)
      .first()
  }

  private fun toolWindowTree(panel: MetroToolWindowPanel): Tree = panel.tree

  /** Waits for the callback-owned pane; its tree is separate from the graph browser. */
  private fun waitForValidationResults(panel: MetroToolWindowPanel): MetroValidationResultPanel {
    var results: MetroValidationResultPanel? = null
    object : WaitFor(30_000) {
        override fun condition(): Boolean {
          PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
          results =
            com.intellij.util.ui.UIUtil.findComponentOfType(
              panel,
              MetroValidationResultPanel::class.java,
            )
          return results?.isVisible == true
        }
      }
      .assertCompleted("The requested validation should show its result")
    return checkNotNull(results)
  }

  /** Expands lazy result rows through the platform model before reading their display data. */
  private fun treeNodes(tree: Tree): List<MetroTreeNode> {
    PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))
    return (0 until tree.rowCount).mapNotNull { row ->
      val path = tree.getPathForRow(row)
      TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element as? MetroTreeNode
    }
  }

  private fun selectedTreeNode(tree: Tree): MetroTreeNode? {
    val path = tree.selectionPath ?: return null
    return TreeUtil.getLastUserObject(NodeDescriptor::class.java, path)?.element as? MetroTreeNode
  }

  private fun toolWindowStatus(panel: MetroToolWindowPanel): IndexBuildStatusPanel {
    return com.intellij.util.ui.UIUtil.findComponentOfType(
      panel,
      IndexBuildStatusPanel::class.java,
    ) ?: error("Metro tool window has no index build status")
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

  fun testRefreshedNodesReplaceBindingsWhoseKeyDidNotChange() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Api

        @Inject class FirstApi : Api
        @Inject class SecondApi : Api

        interface ApiBindings {
          @Binds fun bindApi(impl: FirstApi): Api
        }

        @DependencyGraph(bindingContainers = [ApiBindings::class])
        interface AppGraph
        """
      )
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graph = structure.children(root).single()
    val before =
      structure.children(graph).single { it.text == "Unscoped" } as MetroTreeNode.Category
    assertTrue("Api -> FirstApi" in structure.children(before).map { it.text })

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val implementationOffset = document.text.indexOf("impl: FirstApi") + "impl: ".length
    WriteCommandAction.runWriteCommandAction(project) {
      document.replaceString(
        implementationOffset,
        implementationOffset + "FirstApi".length,
        "SecondApi",
      )
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    project.service<MetroResolutionService>().awaitIndex(file)

    val after = structure.children(graph).single { it.text == "Unscoped" } as MetroTreeNode.Category
    assertFalse(before == after)
    val rows = structure.children(after).map { it.text }
    assertTrue(rows.toString(), "Api -> SecondApi" in rows)
    assertFalse(rows.toString(), "Api -> FirstApi" in rows)
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

  fun testValidationRefreshThroughPlatformTreeModel() {
    val file = configure()
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

    val root = treeStructure.rootElement as MetroTreeNode
    val graph = treeStructure.children(root).single() as MetroTreeNode.Graph
    project.service<MetroGraphValidationService>().validate(file, graph.context)
    PlatformTestUtil.waitForFuture(treeModel.invalidateAsync(), 30_000)
    assertTrue(visibleTexts().toString(), "Validation" in visibleTexts())

    val document = checkNotNull(PsiDocumentManager.getInstance(project).getDocument(file))
    val memberOffset = document.text.indexOf("val consumer: Consumer")
    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(memberOffset, "val missing: Long\n        ")
    }
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    project.service<MetroResolutionService>().awaitIndex(file)

    val currentGraph = treeStructure.children(root).single() as MetroTreeNode.Graph
    project.service<MetroGraphValidationService>().validate(file, currentGraph.context)
    PlatformTestUtil.waitForFuture(treeModel.invalidateAsync(), 30_000)
    val texts = visibleTexts()
    assertTrue(texts.toString(), texts.any { it.startsWith("[Metro/MissingBinding]") })
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

  fun testSameKeyLazyFactoryDiagnosticsNavigateToDistinctParameters() {
    module.addKotlinStdlibLibrary()
    val file =
      myFixture.configureMetroFile(
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
    val declarations = file.declarationsIncludingNested()
    val expectedParameters =
      listOf(declarations.parameter("first"), declarations.parameter("second"))
    val structure = structure()
    val root = structure.rootElement as MetroTreeNode
    val graphNode = structure.children(root).single() as MetroTreeNode.Graph
    project.service<MetroGraphValidationService>().validate(file, graphNode.context)

    val validation =
      structure.children(graphNode).filterIsInstance<MetroTreeNode.Validation>().single()
    val diagnostics = structure.children(validation).filterIsInstance<MetroTreeNode.Diagnostic>()
    assertEquals(
      listOf(MetroDiagnosticId.INVALID_BINDING, MetroDiagnosticId.INVALID_BINDING),
      diagnostics.map { it.diagnostic.id },
    )
    assertTrue(diagnostics.all { "Lazy<Factory>" in it.text })

    val navigableParameters = diagnostics.map { diagnostic ->
      val stackEntries = structure.children(diagnostic).filterIsInstance<MetroTreeNode.StackEntry>()
      val sourceEntry = stackEntries.single { entry ->
        expectedParameters.any { parameter -> entry.pointer?.element === parameter }
      }
      assertTrue(sourceEntry.text, "is injected at" in sourceEntry.text)
      checkNotNull(sourceEntry.pointer?.element)
    }
    assertEquals(expectedParameters.toSet(), navigableParameters.toSet())
  }
}
