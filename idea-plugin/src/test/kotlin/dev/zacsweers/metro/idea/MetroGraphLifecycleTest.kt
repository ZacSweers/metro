// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.diagnostics.MetroGraphLifecycleInspection
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.unused.isMetroImplicitUsage
import org.jetbrains.kotlin.psi.KtFile

class MetroGraphLifecycleTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
    project.setMetroOptions()
    project.service<MetroGraphValidationService>().clearResults()
  }

  fun testBothPhasesResolveSuppliedParametersAndMarkCallbackUsed() {
    val file =
      myFixture.configureMetroFile(
        """
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphInit @OnGraphClose fun lifecycle(value: String) { println(value) }
        @DependencyGraph.Factory fun interface Factory {
          fun create(@Provides value: String): AppGraph
        }
      }
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    val consumers = index.accessorsFor(graph)
    assertEquals(
      setOf(
        ConsumerEntry.GraphRequestKind.INIT_CALLBACK,
        ConsumerEntry.GraphRequestKind.CLOSE_CALLBACK,
      ),
      consumers.mapTo(hashSetOf()) { it.graphRequestKind },
    )
    assertEquals("value", consumers.first().pointer.element?.text?.substringBefore(':'))
    assertEquals(1, index.createResolutionSession().bindingsFor(consumers.first()).size)
    assertTrue(file.declarationsIncludingNested().function("lifecycle").isMetroImplicitUsage())
    assertEmpty(validate(file).diagnostics)
  }

  fun testMissingBindingKeepsCallbackPhaseAndParameterLocation() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Missing
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphClose fun cleanup(missing: Missing) {}
      }
    """,
      )
    val diagnostic = validate(file).diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    assertTrue(diagnostic.render(), "@OnGraphClose" in diagnostic.render())
    assertTrue(diagnostic.render(), "AppGraph.cleanup.missing" in diagnostic.render())
    assertEquals("missing: Missing", diagnostic.stack.last().pointer?.element?.text)
  }

  fun testContributedDiamondAndOverrideSelectOneCallbackPerPhase() {
    val file =
      myFixture.configureMetroFile(
        """
      abstract class AppScope private constructor()
      interface Base {
        @OnGraphInit @OnGraphClose fun lifecycle(value: String) {}
      }
      interface Left : Base
      interface Right : Base
      @ContributesTo(AppScope::class) interface Callbacks : Left, Right, CloseableGraph {
        @OnGraphInit @OnGraphClose override fun lifecycle(value: String) {}
      }
      @DependencyGraph(AppScope::class) interface AppGraph {
        @DependencyGraph.Factory fun interface Factory {
          fun create(@Provides value: String): AppGraph
        }
      }
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single()
    val session = index.createResolutionSession()
    val query = session.queryContext(index.contextsFor(graph).single())!!
    val composition = session.graphComposition(query)
    assertEquals(1, composition.lifecycleCallbacks.size)
    assertEquals(2, composition.accessors.size)
    assertTrue(
      composition.lifecycleCallbacks.single().pointer.element!!.text,
      "override" in composition.lifecycleCallbacks.single().pointer.element!!.text,
    )
    assertEmpty(validate(file).diagnostics)
  }

  fun testZeroParameterContributionStillRequiresCloseableGraph() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      abstract class AppScope private constructor()
      @ContributesTo(AppScope::class) interface Callbacks {
        @OnGraphInit fun initialize() {}
      }
      @DependencyGraph(AppScope::class) interface AppGraph
    """,
        ),
      )
    assertEquals(listOf(MetroDiagnosticId.GRAPH_LIFECYCLE), result.diagnostics.map { it.id })
  }

  fun testDeferredCloseRequestsKeepCloseOnlyWarning() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @Inject class Resource
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphClose fun direct(resource: Resource) {}
        @OnGraphClose fun deferred(resource: () -> Resource) {}
      }
    """,
        ),
      )
    assertEquals(
      1,
      result.diagnostics.count { it.id == MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER },
    )
    assertEquals(2, result.diagnostics.count { it.id == MetroDiagnosticId.CLOSE_ONLY_BINDING })
  }

  fun testWarningsCanBeSuppressedIndependently() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @Inject class Resource
      @DependencyGraph interface AppGraph : CloseableGraph {
        @Suppress("CLOSE_ONLY_BINDING") @OnGraphClose fun unscoped(resource: Resource) {}
        @Suppress("UNSCOPED_LIFECYCLE_PARAMETER") @OnGraphClose fun closeOnly(resource: Resource) {}
      }
    """,
        ),
      )
    assertEquals(2, result.diagnostics.size)
    val unscoped =
      result.diagnostics.single { it.id == MetroDiagnosticId.UNSCOPED_LIFECYCLE_PARAMETER }
    val closeOnly = result.diagnostics.single { it.id == MetroDiagnosticId.CLOSE_ONLY_BINDING }
    assertTrue(
      unscoped.stack.first().pointer?.element?.parent?.parent?.text.orEmpty().contains("unscoped"),
    )
    assertTrue(
      closeOnly.stack
        .first()
        .pointer
        ?.element
        ?.parent
        ?.parent
        ?.text
        .orEmpty()
        .contains("closeOnly"),
    )
  }

  fun testParentOwnedBindingIsScopedAndChildUsePreventsCloseOnlyWarning() {
    val file =
      myFixture.configureMetroFile(
        """
      abstract class AppScope private constructor()
      @SingleIn(AppScope::class) @Inject class Resource
      @GraphExtension interface Child : CloseableGraph {
        @OnGraphInit fun initialize(resource: Resource) {}
      }
      @DependencyGraph(AppScope::class) interface AppGraph : CloseableGraph {
        val child: Child
        @OnGraphClose fun cleanup(resource: Resource) {}
      }
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val results =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)
    for (result in results) {
      assertEmpty(result.requireCompleted().diagnostics)
    }
  }

  fun testManualCloseImplementationIsRejected() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @DependencyGraph interface AppGraph : CloseableGraph {
        override fun close() {}
      }
    """,
        ),
      )
    assertEquals(listOf(MetroDiagnosticId.GRAPH_LIFECYCLE), result.diagnostics.map { it.id })
  }

  fun testSuspendInitializationRequiresSuspendFactory() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphInit suspend fun initialize() {}
        @DependencyGraph.Factory fun interface Factory { fun create(): AppGraph }
      }
    """,
        ),
        suspendProviders = true,
      )
    assertTrue(
      result.diagnostics.joinToString { it.render() },
      result.diagnostics.any {
        it.id == MetroDiagnosticId.GRAPH_LIFECYCLE && "suspend SAM" in it.render()
      },
    )
  }

  fun testSuspendFactoryAndCloseUseOptionalCoroutineRuntime() {
    myFixture.addFileToProject(
      "metro/internal/SuspendDoubleCheck.kt",
      """
      package dev.zacsweers.metro.internal
      class SuspendDoubleCheck
      """
        .trimIndent(),
    )
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @DependencyGraph interface AppGraph : SuspendCloseableGraph {
        @OnGraphInit suspend fun initialize() {}
        @OnGraphClose suspend fun cleanup() {}
        @DependencyGraph.Factory fun interface Factory { suspend fun create(): AppGraph }
      }
    """,
        ),
        suspendProviders = true,
      )
    assertEmpty(result.diagnostics)
  }

  fun testEmptySuspendCloseableGraphRequiresCoroutineRuntimeAndOption() {
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @DependencyGraph interface AppGraph : SuspendCloseableGraph
    """,
        ),
      )
    assertTrue(result.diagnostics.any { it.id == MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED })
    val enabled =
      validate(
        myFixture.configureMetroFile(
          """
      @DependencyGraph interface AppGraph : SuspendCloseableGraph
    """,
        ),
        suspendProviders = true,
      )
    assertTrue(enabled.diagnostics.any { it.id == MetroDiagnosticId.MISSING_RUNTIME_COROUTINES })
  }

  fun testSynchronousParentAndDirectPropertyRejectSuspendChild() {
    val file =
      myFixture.configureMetroFile(
        """
      @GraphExtension interface Child : SuspendCloseableGraph {
        @OnGraphInit suspend fun initialize() {}
        @GraphExtension.Factory fun interface Factory { suspend fun create(): Child }
      }
      @DependencyGraph interface AppGraph : CloseableGraph { val child: Child }
    """,
      )
    project.setMetroOptions("enable-suspend-providers" to "true")
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val parent = index.graphs.single { it.name == "AppGraph" }
    val diagnostics =
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent).flatMap {
        it.requireCompleted().diagnostics
      }
    assertTrue(
      diagnostics.joinToString { it.render() },
      diagnostics.any { "parent cannot own" in it.render() },
    )
    assertTrue(
      diagnostics.joinToString { it.render() },
      diagnostics.any {
        "suspend factory SAM or suspend function" in it.diagnostic.title.toString()
      },
    )
  }

  fun testDeclarationsCallsReferencesAndAnnotatedSuperAreChecked() {
    val file =
      myFixture.configureMetroFile(
        """
      @OnGraphInit fun topLevel() {}
      interface Base {
        @OnGraphInit @OnGraphClose fun lifecycle() {}
        @OnGraphClose fun abstractCallback()
        @OnGraphInit fun invalidResult(): String = ""
        @OnGraphInit fun <T> generic(value: T) {}
      }
      interface Child : Base {
        @OnGraphInit override fun lifecycle() { super.lifecycle() }
        fun use() { lifecycle(); val reference = this::lifecycle }
      }
    """,
      )
    val problems = inspect(file)
    assertEquals(8, problems.size)
    assertEquals(2, problems.count { "cannot be called directly" in it.descriptionTemplate })
    assertEquals(1, problems.count { "Callable references" in it.descriptionTemplate })
    assertEquals(1, problems.count { "repeat @OnGraphClose" in it.descriptionTemplate })
  }

  fun testSuspendDeferredParametersAreExemptFromUnscopedWarning() {
    myFixture.addFileToProject(
      "metro/internal/SuspendDoubleCheck.kt",
      """
      package dev.zacsweers.metro.internal
      class SuspendDoubleCheck
      """
        .trimIndent(),
    )
    val result =
      validate(
        myFixture.configureMetroFile(
          """
      @Inject class Resource
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphClose fun deferred(provider: suspend () -> Resource, lazy: SuspendLazy<Resource>) {}
      }
    """,
        ),
        suspendProviders = true,
      )
    assertEquals(
      listOf(MetroDiagnosticId.CLOSE_ONLY_BINDING, MetroDiagnosticId.CLOSE_ONLY_BINDING),
      result.diagnostics.map { it.id },
    )
  }

  fun testCompiledContributionRetainsCallbacksAndParameterNavigation() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
        @DependencyGraph(libtest.LibLifecycleScope::class) interface AppGraph {
          @DependencyGraph.Factory fun interface Factory {
            fun create(@Provides value: String): AppGraph
          }
        }
      """,
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val graph = index.graphs.single { it.name == "AppGraph" }
      val session = index.createResolutionSession()
      val query = session.queryContext(index.contextsFor(graph).single())!!
      val composition = session.graphComposition(query)
      assertEquals(1, composition.lifecycleCallbacks.size)
      assertEquals(2, composition.accessors.size)
      assertNotNull(composition.accessors.first().pointer.element)
      assertEquals(1, session.bindingsFor(composition.accessors.first(), query).size)
      assertEmpty(validate(file).diagnostics)
    }
  }

  fun testDynamicReplacementValidatesCallbackSuspendDependencies() {
    project.setMetroOptions("enable-suspend-providers" to "true")
    myFixture.addFileToProject(
      "metro/internal/SuspendDoubleCheck.kt",
      """
      package dev.zacsweers.metro.internal
      class SuspendDoubleCheck
      """
        .trimIndent(),
    )
    val file =
      myFixture.configureMetroFile(
        """
      abstract class AppScope private constructor()
      @DependencyGraph(AppScope::class) interface AppGraph : CloseableGraph {
        @Provides @SingleIn(AppScope::class) fun value(): String = "default"
        @OnGraphInit fun initialize(value: String) {}
      }
      @BindingContainer object Replacement {
        @Provides @SingleIn(AppScope::class) suspend fun value(): String = "replacement"
      }
      fun create() = createDynamicGraph<AppGraph>(Replacement)
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val context = index.contextsFor(graph).single { it.dynamicGraph != null }
    val result =
      project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR, diagnostic.id)
    assertTrue(diagnostic.render(), "@OnGraphInit" in diagnostic.render())
  }

  fun testCallbackEditsReplaceRetainedParameterRoots() {
    project.enableImmediateAutomaticRefresh()
    val file =
      myFixture.configureMetroFile(
        """
      @DependencyGraph interface AppGraph : CloseableGraph {
        fun initialize(value: String) {}
      }
    """,
      )
    val service = project.service<MetroResolutionService>()
    val initial = service.awaitIndex(file)
    assertEmpty(initial.accessorsFor(initial.graphs.single()))
    val document = PsiDocumentManager.getInstance(project).getDocument(file)!!
    fun edit(text: String) {
      WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    val original = file.text
    edit(original.replace("fun initialize", "@OnGraphInit fun initialize"))
    val added = service.awaitIndex(file)
    assertEquals(
      listOf(ConsumerEntry.GraphRequestKind.INIT_CALLBACK),
      added.accessorsFor(added.graphs.single()).map { it.graphRequestKind },
    )
    edit(original.replace("fun initialize", "@OnGraphClose fun initialize"))
    val changed = service.awaitIndex(file)
    assertEquals(
      listOf(ConsumerEntry.GraphRequestKind.CLOSE_CALLBACK),
      changed.accessorsFor(changed.graphs.single()).map { it.graphRequestKind },
    )
    edit(original)
    val removed = service.awaitIndex(file)
    assertEmpty(removed.accessorsFor(removed.graphs.single()))
  }

  fun testInheritedOptionalCallbackKeepsOnlyTheOverrideRequest() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Base {
        @OnGraphInit fun initialize(value: String = "default") {}
      }
      @DependencyGraph interface AppGraph : Base, CloseableGraph {
        @OnGraphInit override fun initialize(value: String) {}
      }
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val session = index.createResolutionSession()
    val query = session.queryContext(index.contextsFor(index.graphs.single()).single())!!
    val consumers = session.accessorsFor(query)
    assertEquals(1, consumers.size)
    assertTrue(consumers.single().isOptional)
    assertTrue(consumers.single().lifecycleOwner!!.element!!.text.contains("override"))
    val base =
      index.accessorsFor(index.graphs.single()).single {
        it.lifecycleOwner?.element?.text?.contains("override") == false
      }
    assertFalse(session.isConsumerInContext(base, query))
    assertEmpty(validate(file).diagnostics)
  }

  fun testInvalidCompiledCallbacksAreReportedAtTheConsumerGraph() {
    module.withMetroLibFixtureLibrary {
      val result =
        validate(
          myFixture.configureMetroFile(
            """
        @DependencyGraph interface AppGraph : libtest.LibInvalidLifecycle
      """,
          ),
        )
      assertEquals(2, result.diagnostics.size)
      assertTrue(result.diagnostics.all { it.id == MetroDiagnosticId.GRAPH_LIFECYCLE })
      assertTrue(
        result.diagnostics.all {
          "implemented member functions returning Unit" in it.diagnostic.title.toString()
        },
      )
    }
  }

  private fun validate(
    file: KtFile,
    suspendProviders: Boolean = false,
  ): KaGraphValidationResult.Completed {
    project.setMetroOptions("enable-suspend-providers" to suspendProviders.toString())
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
  }

  private fun inspect(file: KtFile): List<ProblemDescriptor> {
    val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
    val visitor = MetroGraphLifecycleInspection().buildVisitor(holder, true)
    PsiTreeUtil.processElements(file) {
      it.accept(visitor)
      true
    }
    return holder.results
  }
}
