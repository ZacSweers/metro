// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.compiler.graph.GraphLifecyclePhase
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService

class MetroGraphLifecycleTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    module.addMetroRuntimeLibrary()
    project.setMetroOptions("enable-suspend-providers" to "true")
    // The IDE test project uses the published bootstrap runtime.
    myFixture.addFileToProject(
      "dev/zacsweers/metro/Lifecycle.kt",
      """
      package dev.zacsweers.metro
      annotation class OnGraphInit
      annotation class OnGraphClose
      interface CloseableGraph { fun close() }
      interface SuspendCloseableGraph { suspend fun close() }
      """
        .trimIndent(),
    )
  }

  private fun validate(source: String): List<String> {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
      .diagnostics
      .map { it.render() }
  }

  fun testDiamondRequestsEachParameterOnceForBothPhases() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Base {
        @OnGraphInit @OnGraphClose fun run(value: String) {}
      }
      interface Left : Base
      interface Right : Base
      @DependencyGraph interface AppGraph : CloseableGraph, Left, Right
    """,
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    val requests =
      index
        .accessorsFor(checkNotNull(index.queryContext(index.contextsFor(graph).single())))
        .filter { it.lifecycleRequest != null }
    assertEquals(listOf("LIFECYCLE_PARAMETER"), requests.map { it.graphRequestKind?.name })
    assertEquals(
      setOf(GraphLifecyclePhase.INIT, GraphLifecyclePhase.CLOSE),
      requests.single().lifecycleRequest?.phases,
    )
  }

  fun testParametersAreGraphRequests() {
    val diagnostics =
      validate(
        """
      interface Missing
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphClose fun stop(label: String = "", missing: Missing) {}
      }
    """,
      )
    val diagnostic = diagnostics.single()
    assertTrue(diagnostic, diagnostic.contains(MetroDiagnosticId.MISSING_BINDING.fullId))
    // The trace matches the compiler's wording for callback parameters.
    assertTrue(diagnostic, diagnostic.contains("test.AppGraph.stop(…, missing) -> Missing"))
    assertTrue(
      diagnostic,
      diagnostic.contains("Missing is injected at @OnGraphClose test.AppGraph.stop(…, missing)"),
    )
  }

  fun testDefaultedParametersAreOptional() {
    val diagnostics =
      validate(
        """
      @DependencyGraph interface AppGraph : CloseableGraph {
        @OnGraphInit fun start(label: String = "default") {}
      }
    """,
      )
    assertTrue(diagnostics.toString(), diagnostics.isEmpty())
  }

  fun testCallbackWithBothAnnotationsReportsItsParameterOnce() {
    val diagnostics =
      validate(
        """
      @DependencyGraph interface AppGraph : CloseableGraph {
        @Provides suspend fun provideText(): String = ""
        @OnGraphInit @OnGraphClose fun run(text: String) {}
      }
    """,
      )
    val suspendDiagnostics = diagnostics.filter {
      it.contains(MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR.fullId)
    }
    assertEquals(diagnostics.toString(), 1, suspendDiagnostics.size)
  }

  fun testEagerSuspendParametersRequireSuspendCallback() {
    val diagnostics =
      validate(
        """
      @DependencyGraph interface AppGraph : CloseableGraph {
        @Provides suspend fun provideText(): String = ""
        @OnGraphInit fun start(text: String) {}
      }
    """,
      )
    assertTrue(
      diagnostics.toString(),
      diagnostics.any {
        it.contains(MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR.fullId)
      },
    )
  }
}
