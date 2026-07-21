// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService

class MetroSuspendGraphValidationTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  private fun validate(
    source: String,
    suspendProvidersEnabled: Boolean = true,
  ): KaGraphValidationResult.Completed {
    project.setMetroOptions("enable-suspend-providers" to suspendProvidersEnabled.toString())
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().index(file)
    val graph = index.graphs.single { it.name == "AppGraph" }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
  }

  fun testSuspendAccessorAllowsTransitiveSuspendBinding() {
    val result =
      validate(
        """
        class Database
        @Inject class Repository(val database: Database)

        @DependencyGraph
        interface AppGraph {
          suspend fun repository(): Repository

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertEquals(
      setOf("test.Database", "test.Repository"),
      result.suspendKeys.mapTo(mutableSetOf()) { it.renderedType },
    )
  }

  fun testNonSuspendAccessorReportsTransitiveSuspendBinding() {
    val result =
      validate(
        """
        class Database
        @Inject class Repository(val database: Database)

        @DependencyGraph
        interface AppGraph {
          val repository: Repository

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.SUSPEND_BINDING_FROM_NON_SUSPEND_ACCESSOR)
  }

  fun testSuspendFunctionDependencyStopsPropagation() {
    val result =
      validate(
        """
        class Database
        @Inject class Repository(val database: suspend () -> Database)

        @DependencyGraph
        interface AppGraph {
          val repository: Repository

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
    assertEquals(
      setOf("test.Database"),
      result.suspendKeys.mapTo(mutableSetOf()) { it.renderedType },
    )
  }

  fun testSynchronousProviderCannotWrapSuspendBinding() {
    val result =
      validate(
        """
        class Database
        @Inject class Repository(val database: () -> Database)

        @DependencyGraph
        interface AppGraph {
          val repository: Repository

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.SUSPEND_BINDING_WRAPPED_IN_PROVIDER)
  }

  fun testSetCannotAggregateSuspendBindings() {
    val result =
      validate(
        """
        @DependencyGraph
        interface AppGraph {
          val values: Set<String>

          @Provides @IntoSet suspend fun provideValue(): String = "value"
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS)
  }

  fun testSuspendProviderMapDefersEachSuspendBinding() {
    val result =
      validate(
        """
        @MapKey annotation class StringKey(val value: String)

        class Value

        @DependencyGraph
        interface AppGraph {
          val values: Map<String, suspend () -> Value>

          @Provides @IntoMap @StringKey("value")
          suspend fun provideValue(): Value = Value()
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testScalarMapCannotAggregateSuspendBindings() {
    val result =
      validate(
        """
        @MapKey annotation class StringKey(val value: String)

        class Value

        @DependencyGraph
        interface AppGraph {
          val values: Map<String, Value>

          @Provides @IntoMap @StringKey("value")
          suspend fun provideValue(): Value = Value()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.MULTIBINDING_OVER_SUSPEND_BINDINGS)
  }

  fun testMemberInjectorCannotConsumeSuspendBinding() {
    val result =
      validate(
        """
        class Database

        class Target {
          @Inject lateinit var database: Database
        }

        @DependencyGraph
        interface AppGraph {
          fun inject(target: Target)

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING)
  }

  fun testAssistedFactoryFunctionMustBeSuspend() {
    val result =
      validate(
        """
        class Database

        @AssistedInject
        class Target(@Assisted val name: String, val database: Database) {
          @AssistedFactory
          interface Factory {
            fun create(name: String): Target
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Target.Factory

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED)
  }

  fun testAssistedTargetMemberInjectionReportsMemberError() {
    val result =
      validate(
        """
        class Database

        @AssistedInject
        class Target(@Assisted val name: String) {
          @Inject lateinit var database: Database

          @AssistedFactory
          interface Factory {
            fun create(name: String): Target
          }
        }

        @DependencyGraph
        interface AppGraph {
          val factory: Target.Factory

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.MEMBER_INJECTION_OVER_SUSPEND_BINDING)
    assertFalse(
      result.diagnostics.joinToString { it.render() },
      result.diagnostics.any { it.id == MetroDiagnosticId.ASSISTED_FACTORY_SUSPEND_REQUIRED },
    )
  }

  fun testAssistedTargetDependenciesRemainDeferredForCycleDetection() {
    val result =
      validate(
        """
        @Inject class Dependency(val factory: Target.Factory)

        @AssistedInject
        class Target(@Assisted val name: String, val dependency: Dependency) {
          @AssistedFactory
          interface Factory {
            fun create(name: String): Target
          }
        }

        @DependencyGraph
        interface AppGraph {
          val dependency: Dependency
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testIncludedGraphCanPassThroughExactSuspendWrapper() {
    val result =
      validate(
        """
        class Database

        interface ParentGraph {
          val database: suspend () -> Database
        }

        @DependencyGraph
        interface AppGraph {
          val database: suspend () -> Database

          @DependencyGraph.Factory
          interface Factory {
            fun create(@Includes parent: ParentGraph): AppGraph
          }
        }
        """
      )

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testSuspendUseReportsWhenFeatureIsDisabled() {
    val result =
      validate(
        """
        class Database

        @DependencyGraph
        interface AppGraph {
          suspend fun database(): Database

          @Provides suspend fun provideDatabase(): Database = Database()
        }
        """,
        suspendProvidersEnabled = false,
      )

    assertDiagnostic(result, MetroDiagnosticId.SUSPEND_PROVIDERS_NOT_ENABLED)
  }

  fun testScopedSuspendBindingRequiresRuntimeCoroutines() {
    val result = validate(scopedSuspendBindingSource())

    assertDiagnostic(result, MetroDiagnosticId.MISSING_RUNTIME_COROUTINES)
  }

  fun testScopedSuspendBindingAcceptsRuntimeCoroutines() {
    myFixture.addFileToProject(
      "metro/internal/SuspendDoubleCheck.kt",
      """
      package dev.zacsweers.metro.internal

      class SuspendDoubleCheck
      """
        .trimIndent(),
    )

    val result = validate(scopedSuspendBindingSource())

    assertTrue(result.diagnostics.joinToString { it.render() }, result.diagnostics.isEmpty())
  }

  fun testSuspendLazyRequiresRuntimeWithoutSuspendBindings() {
    myFixture.addFileToProject(
      "metro/SuspendLazy.kt",
      """
      package dev.zacsweers.metro

      interface SuspendLazy<out T>
      """
        .trimIndent(),
    )
    val result =
      validate(
        """
        @Inject class Consumer(val value: SuspendLazy<String>)

        @DependencyGraph
        interface AppGraph {
          val consumer: Consumer

          @Provides fun provideValue(): String = "value"
        }
        """
      )

    assertDiagnostic(result, MetroDiagnosticId.MISSING_RUNTIME_COROUTINES)
    assertTrue(result.suspendKeys.isEmpty())
  }

  private fun scopedSuspendBindingSource(): String =
    """
    @Scope annotation class RequestScope

    class Database

    @RequestScope
    @DependencyGraph
    interface AppGraph {
      suspend fun database(): Database

      @RequestScope @Provides suspend fun provideDatabase(): Database = Database()
    }
    """

  private fun assertDiagnostic(
    result: KaGraphValidationResult.Completed,
    id: MetroDiagnosticId,
  ) {
    assertTrue(
      result.diagnostics.joinToString { it.render() },
      result.diagnostics.any { it.id == id },
    )
  }
}
