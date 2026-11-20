// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import org.junit.Test

class ContributedGraphExtensionTest : MetroCompilerTest() {
  @Test
  fun `unused graph input from previous compilation reports callable context`() {
    val firstCompilation =
      compile(
        source(
          """
          abstract class LoggedInScope
          class Prefix
          class Suffix
          class Value

          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val value: Value

            @Provides
            fun provideValue(prefix: Prefix, suffix: Suffix): Value = Value()

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(
                @Provides prefix: Prefix,
                @Provides unused: Int,
                @Provides suffix: Suffix,
              ): LoggedInGraph
            }
          }
          """
            .trimIndent(),
        ),
      )

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
      previousCompilationResult = firstCompilation,
      options =
        metroOptions
          .toBuilder()
          .unusedGraphInputsSeverity(MetroOptions.DiagnosticSeverity.WARN)
          .build(),
    ) {
      assertContains("No source location available - graph input declared at")
      assertContains("test.LoggedInGraph.Factory.createLoggedInGraph(…, Int, …)")
      assertThat(messages).doesNotContain("(context)")
    }
  }

  @Test
  fun `contributed graph can inject an empty declared multibinding from parent`() {
    compile(
      source(
        """
        interface MultiboundType
        abstract class LoggedInScope

        @Inject
        class MultiImpl : MultiboundType

        @ContributesTo(AppScope::class)
        interface MultibindingsModule2 {
          // Important for @Multibinds to be used for this test's coverage, as opposed to @ElementsIntoSet
          @Multibinds(allowEmpty = true)
          fun provideMulti(): Set<@JvmSuppressWildcards MultiboundType>
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val multi: Set<MultiboundType>

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Any>("multi")).isNotNull()
    }
  }

  @Test
  fun `a scoped @ContributesBinding class can be accessed in a child graph without an explicit accessor when associated hints are enabled (multi-module)`() {
    val injectDepCompilation =
      compile(
        source(
          """
          interface Bob

          @Inject
          @SingleIn(AppScope::class)
          @ContributesBinding(AppScope::class)
          class Dependency : Bob
          """
            .trimIndent(),
        ),
      )

    val graphExtensionCompilation =
      compile(
        source(
          """
          sealed interface LoggedInScope

          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Bob

              @GraphExtension.Factory @ContributesTo(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
          """
            .trimIndent(),
        ),
        previousCompilationResult = injectDepCompilation,
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
      compilationBlock = {
        addPreviousResultToClasspath(injectDepCompilation)
        addPreviousResultToClasspath(graphExtensionCompilation)
      },
    ) {
      val parentGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(childGraph.callProperty<Any>("childDependency")).isNotNull()
    }
  }

  @Test
  fun `a scoped inject class can be accessed in a child graph without an explicit accessor when associated hints are enabled (multi-module & direct scope like @Singleton used)`() {
    val injectDepCompilation =
      compile(
        source(
          """
          @Scope annotation class Singleton

          @Inject
          @Singleton
          class Dependency
          """
            .trimIndent(),
        ),
      )

    val graphExtensionCompilation =
      compile(
        source(
          """
          sealed interface LoggedInScope

          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Dependency

              @GraphExtension.Factory @ContributesTo(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
          """
            .trimIndent(),
        ),
        previousCompilationResult = injectDepCompilation,
      )

    compile(
      source(
        """
        @Singleton
        @DependencyGraph(AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
      compilationBlock = {
        addPreviousResultToClasspath(injectDepCompilation)
        addPreviousResultToClasspath(graphExtensionCompilation)
      },
    ) {
      val parentGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(childGraph.callProperty<Any>("childDependency")).isNotNull()
    }
  }

  @Test
  fun `bindings can be replaced in contributed graphs`() {
    val commonCompilation =
      compile(
        source(
          """
          interface ContributedInterface
          abstract class LoggedInScope
          """
            .trimIndent(),
        ),
      )

    val libCompilation1 =
      compile(
        source(
          """
          @Inject
          @SingleIn(LoggedInScope::class)
          @ContributesBinding(LoggedInScope::class)
          class Impl1 : ContributedInterface
          """
            .trimIndent(),
        ),
        compilationBlock = { addPreviousResultToClasspath(commonCompilation) },
      )

    val libCompilation2 =
      compile(
        source(
          """
          @Inject
          @SingleIn(LoggedInScope::class)
          @ContributesBinding(LoggedInScope::class, replaces = [Impl1::class])
          class Impl2(
            val impl1: Impl1
          ) : ContributedInterface
          """
            .trimIndent(),
        ),
        compilationBlock = {
          addPreviousResultToClasspath(commonCompilation)
          addPreviousResultToClasspath(libCompilation1)
        },
      )

    compile(
      source(
        """
        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val contributedInterface: ContributedInterface
          val impl1: Impl1

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent(),
      ),
      compilationBlock = {
        addPreviousResultToClasspath(commonCompilation)
        addPreviousResultToClasspath(libCompilation2)
        addPreviousResultToClasspath(libCompilation1)
      },
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val impl2 = loggedInGraph.callProperty<Any>("contributedInterface")
      assertThat(impl2.javaClass.simpleName).isEqualTo("Impl2")
      assertThat(impl2).isSameInstanceAs(loggedInGraph.callProperty<Any>("contributedInterface"))
      val impl1 = impl2.callProperty<Any>("impl1")
      assertThat(impl1).isSameInstanceAs(loggedInGraph.callProperty<Any>("impl1"))
    }
  }

  /** A more complex version of the `ProvidedRootReusesConstructorInjectedType.kt` box test. */
  // https://github.com/ZacSweers/metro/issues/866
  // Not in compiler-tests because for some reason IR diagnostics don't write in multi-compilation
  @Test
  fun `empty multibinds reporting in contributed graph`() {
    val firstCompilation =
      compile(
        source(
          """
          abstract class LoginScope

          @GraphExtension(LoginScope::class)
          interface LoginGraph {
            @Multibinds
            fun multibinds(): Map<KClass<*>, Any>

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun create(): LoginGraph
            }
          }
          """
            .trimIndent(),
          extraImports = arrayOf("kotlin.reflect.KClass"),
        ),
      )

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface MainGraph
        """
          .trimIndent(),
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
      previousCompilationResult = firstCompilation,
    ) {
      assertDiagnostics(
        """
        e: MainGraph.kt [Metro/EmptyMultibinding] Multibinding Map<KClass<*>, Any> was unexpectedly empty

          help: annotate its declaration with `@Multibinds(allowEmpty = true)` if it can legitimately be
                empty
          docs: https://zacsweers.github.io/metro/latest/diagnostics/#emptymultibinding
        """
          .trimIndent(),
      )
    }
  }
}
