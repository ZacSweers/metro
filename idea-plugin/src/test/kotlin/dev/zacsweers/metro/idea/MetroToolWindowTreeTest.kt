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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.platform.eel.fs.EelFiles
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
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
import dev.zacsweers.metro.idea.toolwindow.ExportGraphDebugInfoAction
import dev.zacsweers.metro.idea.toolwindow.GraphContextSelectorAction
import dev.zacsweers.metro.idea.toolwindow.IndexBuildStatusPanel
import dev.zacsweers.metro.idea.toolwindow.LoadOrRefreshGraphsAction
import dev.zacsweers.metro.idea.toolwindow.MetroGraphDebugExporter
import dev.zacsweers.metro.idea.toolwindow.MetroToolWindowPanel
import dev.zacsweers.metro.idea.toolwindow.MetroTreeNode
import dev.zacsweers.metro.idea.toolwindow.MetroTreeStructure
import dev.zacsweers.metro.idea.toolwindow.ValidateMetroGraphAction
import dev.zacsweers.metro.idea.toolwindow.ValidateSelectedGraphAction
import dev.zacsweers.metro.idea.toolwindow.ValidationStatusPanel
import dev.zacsweers.metro.idea.toolwindow.writeGraphDebugReport
import java.nio.file.Files
import javax.swing.JPanel
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile

/** Walks [MetroTreeStructure] directly, without Swing, and asserts the produced rows. */
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
    val realIndex = project.service<MetroResolutionService>().index(file)
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

    currentIndex = BindingIndex(emptyList(), emptyList(), emptyList(), emptyList())
    assertTrue(structure.children(root).isEmpty())
    assertNull(pinService.pinnedPath)
  }

  fun testGraphContextSelectorAcquiresReadAccess() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val structure =
      MetroTreeStructure(project, indexProvider = { index }, pinService = project.service()) {
        filter
      }
    val application = ApplicationManager.getApplication()
    val action =
      GraphContextSelectorAction(project.service()) {
        assertTrue(application.isReadAccessAllowed)
        structure.availableContexts()
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

    panel.clear()
    assertFalse(panel.isVisible)
  }

  fun testValidationStatusPanelShowsPreparingAndCountedProgress() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
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
    val index = project.service<MetroResolutionService>().index(file)
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

  fun testToolWindowPanelRecoversAfterDumbMode() {
    val file = configure()
    project.service<MetroResolutionService>().index(file)
    var panel: MetroToolWindowPanel? = null
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      panel = MetroToolWindowPanel(project)
      assertEquals(0, toolWindowTree(checkNotNull(panel)).rowCount)
      val status = toolWindowStatus(checkNotNull(panel))
      assertTrue(status.isVisible)
      assertEquals("Waiting for IDE indexing to finish", status.messageLabel.text)
    }

    val tree = toolWindowTree(checkNotNull(panel))
    PlatformTestUtil.waitForPromise(TreeUtil.promiseExpandAll(tree))
    assertTrue("The Metro tree should populate when smart mode resumes", tree.rowCount > 0)
    assertFalse(toolWindowStatus(checkNotNull(panel)).isVisible)
    com.intellij.openapi.util.Disposer.dispose(checkNotNull(panel))
  }

  fun testDisposedToolWindowPanelIgnoresValidationRequests() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single()
    val context = index.contextsFor(graph).single()
    val panel = MetroToolWindowPanel(project)
    Disposer.dispose(panel)

    panel.selectAndValidate(checkNotNull(graph.classId), file.virtualFile)

    assertNull(project.service<MetroGraphValidationService>().cachedResult(file, context))
  }

  fun testMissingRequestedGraphDoesNotValidateTheSelectedGraph() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
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
      val index = project.service<MetroResolutionService>().index(file)
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
    val index = project.service<MetroResolutionService>().index(file)
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
    val index = project.service<MetroResolutionService>().index(file)
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
    val index = project.service<MetroResolutionService>().index(file)
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

    val stale = checkNotNull(exporter.report(context))
    assertTrue(stale, "freshness=stale" in stale)
    assertTrue(stale, "state=completed" in stale)
    assertTrue(stale, "graphRequests=2" in stale)
    assertSame(result, checkNotNull(validation.cachedResult(file, context)).result)
  }

  fun testExportGraphDebugInfoActionRequiresAnExactGraphSelection() {
    val file = configure()
    val index = project.service<MetroResolutionService>().index(file)
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

  private fun toolWindowTree(panel: MetroToolWindowPanel): Tree {
    return com.intellij.util.ui.UIUtil.findComponentOfType(panel, Tree::class.java)
      ?: error("Metro tool window has no tree")
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
