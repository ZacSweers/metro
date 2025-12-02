// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.ParentGraph
import dev.zacsweers.metro.compiler.allSupertypes
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.invokeInstanceMethod
import dev.zacsweers.metro.compiler.newInstanceStrict
import kotlin.test.assertNotNull
import org.junit.Test

class ContributedGraphExtensionTest : MetroCompilerTest() {
  @Test
  fun simple() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `simple - abstract class`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        abstract class LoggedInGraph {
          abstract val int: Int

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `multiple modules`() {
    val firstCompilation =
      compile(
        source(
          """
          abstract class LoggedInScope

          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val int: Int

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }
          """
            .trimIndent()
        )
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `multiple modules including contributed`() {
    val loggedInScope =
      compile(
        source(
          """
          abstract class LoggedInScope
          """
            .trimIndent()
        )
      )

    val loggedInGraph =
      compile(
        source(
          """
          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }
          """
            .trimIndent()
        ),
        previousCompilationResult = loggedInScope,
      )

    val contributor =
      compile(
        source(
          """
          @ContributesTo(LoggedInScope::class)
          interface LoggedInStringProvider {
            @Provides fun provideString(int: Int): String = int.toString()
          }
          """
            .trimIndent()
        ),
        previousCompilationResult = loggedInScope,
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      compilationBlock = {
        addPreviousResultToClasspath(loggedInScope)
        addPreviousResultToClasspath(loggedInGraph)
        addPreviousResultToClasspath(contributor)
      },
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }

  @Test
  fun `single complex module with contributed`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @ContributesTo(LoggedInScope::class)
        interface LoggedInStringProvider {
          @Provides fun provideString(int: Int): String = int.toString()
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }

  @Test
  fun `multiple modules including contributed binding`() {
    val loggedInScope =
      compile(
        source(
          """
          abstract class LoggedInScope
          """
            .trimIndent()
        )
      )

    val contributedInterface =
      compile(
        source(
          """
          interface ContributedInterface
          """
            .trimIndent()
        )
      )

    val loggedInGraph =
      compile(
        source(
          """
          @GraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val contributedInterface: ContributedInterface

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }
          """
            .trimIndent()
        ),
        compilationBlock = {
          addPreviousResultToClasspath(loggedInScope)
          addPreviousResultToClasspath(contributedInterface)
        },
      )

    val contributor =
      compile(
        source(
          """
          @ContributesBinding(LoggedInScope::class)
          @Inject
          class Impl : ContributedInterface
          """
            .trimIndent()
        ),
        compilationBlock = {
          addPreviousResultToClasspath(loggedInScope)
          addPreviousResultToClasspath(contributedInterface)
        },
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      ),
      compilationBlock = {
        addPreviousResultToClasspath(loggedInScope)
        addPreviousResultToClasspath(loggedInGraph)
        addPreviousResultToClasspath(contributedInterface)
        addPreviousResultToClasspath(contributor)
      },
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Any>("contributedInterface")).isNotNull()
    }
  }

  @Test
  fun `single complex module with contributed binding`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        interface ContributedInterface

        @ContributesBinding(LoggedInScope::class)
        @Inject
        class Impl : ContributedInterface


        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val contributedInterface: ContributedInterface

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Any>("contributedInterface")).isNotNull()
    }
  }

  @Test
  fun `contributed graph can inject multibinding from parent`() {
    compile(
      source(
        """
        abstract class LoggedInScope
        interface ContributedInterface
        class Impl1 : ContributedInterface
        interface ConsumerInterface

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val consumer: ConsumerInterface

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @ContributesBinding(LoggedInScope::class)
        class MultibindingConsumer @Inject constructor(val contributions: Set<ContributedInterface>) : ConsumerInterface

        @ContributesTo(AppScope::class)
        interface MultibindingsModule {

          @Provides
          @ElementsIntoSet
          fun provideImpl1(): Set<ContributedInterface> = setOf(Impl1())
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val contributions: Set<ContributedInterface>
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(
          loggedInGraph.callProperty<Any>("consumer").callProperty<Set<Any>>("contributions").map {
            it.javaClass.canonicalName
          }
        )
        .isEqualTo(listOf("test.Impl1"))
      assertThat(
          exampleGraph.callProperty<Set<Any>>("contributions").map { it.javaClass.canonicalName }
        )
        .isEqualTo(listOf("test.Impl1"))
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
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Any>("multi")).isNotNull()
    }
  }

  @Test
  fun `contributed graph copies scope annotations`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @SingleIn(Unit::class)
        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @ContributesTo(LoggedInScope::class)
        interface LoggedInStringProvider {
          @Provides
          @SingleIn(Unit::class)
          fun provideString(int: Int): String = int.toString()
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }

  @Test
  fun `contributed graph can extend a generic interface`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        interface BaseExtension<T> {
          val value: T
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph : BaseExtension<Int> {

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("value")).isEqualTo(0)
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/pull/351
  @Test
  fun `contributed graph factory can extend a generic interface with create graph function`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        interface GraphExtensionFactory<T> {
          fun createGraph(): T
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory : GraphExtensionFactory<LoggedInGraph>
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)
    }
  }

  @Test
  fun `params are forwarded - provides`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(@Provides long: Long): LoggedInGraph
          }
        }

        @ContributesTo(LoggedInScope::class)
        interface LoggedInStringProvider {
          @Provides
          fun provideString(int: Int, long: Long): String = (int + long).toString()
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph", 2L)
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("2")
    }
  }

  @Test
  fun `params are forwarded - qualified provides`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(@Provides @Named("long") long: Long): LoggedInGraph
          }
        }

        @ContributesTo(LoggedInScope::class)
        interface LoggedInStringProvider {
          @Provides
          fun provideString(int: Int, @Named("long") long: Long): String = (int + long).toString()
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph", 2L)
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("2")
    }
  }

  @Test
  fun `params are forwarded - includes`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(@Includes stringProvider: StringProvider): LoggedInGraph
          }
        }

        class StringProvider(val value: String = "Hello")

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val stringProvider = classLoader.loadClass("test.StringProvider").newInstanceStrict("Hello")
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph", stringProvider)
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("Hello")
    }
  }

  @Test
  fun `params are forwarded - extends`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val string: String

          @GraphExtension.Factory
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @GraphExtension(scope = Unit::class)
        interface StringGraph : LoggedInGraph.Factory {
          val string: String

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(@Provides string: String): StringGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val stringGraph = exampleGraph.invokeInstanceMethod<Any>("create", "Hello")
      val loggedInGraph = stringGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("Hello")
    }
  }

  @Test
  fun `contributed graph factories can be excluded`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(
          scope = AppScope::class,
          excludes = [LoggedInGraph.Factory::class]
        )
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      assertNotNull(ExampleGraph.generatedImpl().createGraphWithNoArgs())
      // Assert no LoggedInGraphImpl or createLoggedInGraph method or parent interface
      assertThat(ExampleGraph.allSupertypes().map { it.name })
        .doesNotContain("test.LoggedInGraph\$Factory")
      assertThat(ExampleGraph.classes.map { it.simpleName })
        .doesNotContain("$\$ContributedLoggedInGraph")
    }
  }

  @Test
  fun `contributed graph can be excluded`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(
          scope = AppScope::class,
          excludes = [LoggedInGraph::class]
        )
        interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      assertNotNull(ExampleGraph.generatedImpl().createGraphWithNoArgs())
      // Assert no LoggedInGraphImpl or createLoggedInGraph method or parent interface
      assertThat(ExampleGraph.allSupertypes().map { it.name })
        .doesNotContain("test.LoggedInGraph\$Factory")
      assertThat(ExampleGraph.classes.map { it.simpleName })
        .doesNotContain("$\$ContributedLoggedInGraph")
    }
  }

  @Test
  fun `contributed graphs can be chained`() {
    compile(
      source(
        """
        abstract class LoggedInScope
        abstract class ProfileScope

        @GraphExtension(ProfileScope::class)
        interface ProfileGraph {
          val string: String

          @GraphExtension.Factory @ContributesTo(LoggedInScope::class)
          interface Factory {
            fun createProfileGraph(): ProfileGraph
          }
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @Provides fun provideString(int: Int): String = int.toString()

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)

      val profileGraph = loggedInGraph.callFunction<Any>("createProfileGraph")
      assertThat(profileGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }

  @Test
  fun `contributed graph factories must be interfaces`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          abstract class Factory {
            abstract fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: LoggedInScope.kt:13:18 Contributed @GraphExtension.Factory declarations can only be interfaces.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed factories must be nested classes of contributed graph - top level`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

        }
        @GraphExtension.Factory @ContributesTo(AppScope::class)
        interface Factory {
          fun createLoggedInGraph(): LoggedInGraph
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: LoggedInScope.kt:8:1 @GraphExtension.Factory declarations must be nested within the contributed graph they create but was top-level.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed factories must be nested classes of contributed graph - wrong class`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int
        }

        interface SomewhereElse {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: LoggedInScope.kt:8:1 @GraphExtension.Factory declarations must be nested within the contributed graph they create but was test.SomewhereElse.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed factories must only contribute contributed graphs`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
          @GraphExtension.Factory @ContributesTo(LoggedInScope::class)
          interface Factory {
            fun createExampleGraph(): ExampleGraph
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: LoggedInScope.kt:8:1 @GraphExtension.Factory abstract function 'createExampleGraph' must return a graph extension but found test.ExampleGraph.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed factories must contribute to a different scope`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int
          @GraphExtension.Factory @ContributesTo(LoggedInScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: LoggedInScope.kt:11:3 GraphExtension.Factory declarations must contribute to a different scope than their contributed graph. However, this factory and its contributed graph both contribute to 'test.LoggedInScope'.
        """
          .trimIndent()
      )
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/359
  @Test
  fun `Contributed GraphExtension can access providers from interface contributed to parent`() {
    compile(
      source(
        """
        sealed interface TestScope
        sealed interface TestChildScope

        @ContributesTo(TestScope::class)
        public interface TestContribution {
            @Provides
            public fun provideString(): String = ""
        }

        @SingleIn(TestScope::class)
        @DependencyGraph(scope = TestScope::class)
        interface ParentGraph

        @GraphExtension(TestChildScope::class)
        interface ChildGraph {
            val string: String

            @GraphExtension.Factory @ContributesTo(TestScope::class)
            interface Factory {
                fun createChildGraph(): ChildGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ParentGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createChildGraph")
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("")
    }
  }

  // https://github.com/ZacSweers/metro/issues/377
  @Test
  fun `constructor injected class is automatically added in parent scope`() {
    // Previous name but migrated with @GraphExtension migration: "suggest adding to parent if
    // scoped constructor-injected class matches parent scope but isn't provided"
    compile(
      source(
        """
        sealed interface LoggedInScope

        @Inject @SingleIn(AppScope::class) class Dependency
        @Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          // Works if added explicitly like this
          // val dependency: Dependency
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
            val childDependency: ChildDependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val childDep1 = loggedInGraph.callProperty<Any>("childDependency")
      val dep1 = childDep1.callProperty<Any>("dep")
      val childDep2 = loggedInGraph.callProperty<Any>("childDependency")
      val dep2 = childDep2.callProperty<Any>("dep")

      assertThat(childDep2).isSameInstanceAs(childDep1)
      assertThat(dep2).isSameInstanceAs(dep1)
    }
  }

  @Test
  fun `scoped bindings are automatically kept across intermediate graphs`() {
    // Previous name but migrated with @GraphExtension migration: "suggest adding to parent if
    // scoped constructor-injected class matches parent's parent scope but isn't provided"
    compile(
      source(
        """
        sealed interface IntermediateScope
        sealed interface LoggedInScope

        @Inject @SingleIn(AppScope::class) class Dependency
        @Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          // Works if added explicitly like this
          // val dependency: Dependency
        }

        @GraphExtension(IntermediateScope::class)
        interface IntermediateGraph {
            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createIntermediateGraph(): IntermediateGraph
            }
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
            val childDependency: ChildDependency

            @GraphExtension.Factory @ContributesTo(IntermediateScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val intermediateGraph = graph.callFunction<Any>("createIntermediateGraph")
      val loggedInGraph = intermediateGraph.callFunction<Any>("createLoggedInGraph")
      val childDep1 = loggedInGraph.callProperty<Any>("childDependency")
      val dep1 = childDep1.callProperty<Any>("dep")
      val childDep2 = loggedInGraph.callProperty<Any>("childDependency")
      val dep2 = childDep2.callProperty<Any>("dep")

      assertThat(childDep2).isSameInstanceAs(childDep1)
      assertThat(dep2).isSameInstanceAs(dep1)
    }
  }

  @Test
  fun `contributed graph with qualified provider`() {
    compile(
      source(
        """
        abstract class Parent

        @GraphExtension(Parent::class)
        interface ParentGraph {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(
                @Provides @ForScope(Parent::class) string: String
            ): ParentGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `a scoped inject class can be accessed in a child graph without an explicit accessor when associated hints are enabled`() {
    compile(
      source(
        """
        sealed interface LoggedInScope

        @Inject @SingleIn(AppScope::class) class Dependency
        @Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val childDependency: ChildDependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(childGraph.callProperty<Any>("childDependency")).isNotNull()
    }
  }

  @Test
  fun `a scoped inject class can be accessed in a child graph without an explicit accessor when associated hints are enabled (direct scope like @Singleton used)`() {
    compile(
      source(
        """
        sealed interface LoggedInScope
        @Scope annotation class Singleton

        @Inject
        @Singleton
        class Dependency

        @Singleton
        @DependencyGraph(AppScope::class)
        interface ExampleGraph

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val childDependency: Dependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(childGraph.callProperty<Any>("childDependency")).isNotNull()
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
            .trimIndent()
        )
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
            .trimIndent()
        ),
        previousCompilationResult = injectDepCompilation,
      )

    compile(
      source(
        """
        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph
        """
          .trimIndent()
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
            .trimIndent()
        )
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
            .trimIndent()
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
          .trimIndent()
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
  fun `a scoped inject class can be accessed in parallel child graphs without an explicit accessor when associated hints are enabled`() {
    compile(
      source(
        """
        interface LoggedInScope
        interface OtherScope

        @Inject
        @SingleIn(AppScope::class)
        class Dependency

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val childDependency: Dependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }

        @GraphExtension(OtherScope::class)
        interface OtherGraph {
          val childDependency: Dependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createOtherGraph(): OtherGraph
            }
        }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val childGraph1 = parentGraph.callFunction<Any>("createLoggedInGraph")
      val childGraph2 = parentGraph.callFunction<Any>("createOtherGraph")

      val childGraph1Dep = childGraph1.callProperty<Any>("childDependency")
      assertThat(childGraph1Dep).isNotNull()
      assertThat(childGraph1Dep).isSameInstanceAs(childGraph2.callProperty<Any>("childDependency"))

      val childGraph2Dep = childGraph2.callProperty<Any>("childDependency")
      assertThat(childGraph2Dep).isNotNull()
      assertThat(childGraph2Dep).isSameInstanceAs(childGraph1.callProperty<Any>("childDependency"))
    }
  }

  @Test
  fun `Contributed GraphExtension can provide multibindings`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        @DependencyGraph(AppScope::class)
        interface ExampleGraph

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val ints: Set<Int>
          @Provides @IntoSet fun provideInt1(): Int = 1
          @Provides @IntoSet fun provideInt2(): Int = 2
          @Provides
          @ElementsIntoSet
          fun provideInts(): Set<Int> = setOf(3, 4)

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val ints = loggedInGraph.callProperty<Set<Int>>("ints")
      assertThat(ints).isNotNull()
      assertThat(ints).containsExactly(1, 2, 3, 4)
    }
  }

  @Test
  fun `Contributed GraphExtension can provide multibindings via @Binds`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        interface Task
        @Inject class TaskImpl1 : Task
        @Inject class TaskImpl2 : Task

        @DependencyGraph(AppScope::class)
        interface ExampleGraph

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val tasks: Set<Task>
          @IntoSet @Binds val TaskImpl1.bind: Task
          @IntoSet @Binds val TaskImpl2.bind: Task

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val tasks = loggedInGraph.callProperty<Set<Any>>("tasks")
      assertThat(tasks).isNotNull()
      assertThat(tasks.map { it.javaClass.name })
        .containsExactly("test.TaskImpl1", "test.TaskImpl2")
    }
  }

  @Test
  fun `Contributed GraphExtension can access multibindings provided by its parent`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        interface Task
        @Inject class TaskImpl1 : Task
        @Inject class TaskImpl2 : Task

        @DependencyGraph(AppScope::class)
        interface ExampleGraph {
          val tasks: Set<Task>
          @IntoSet @Binds val TaskImpl1.bind: Task
          @IntoSet @Binds val TaskImpl2.bind: Task
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val tasksFromParent: Set<Task>

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val tasks = loggedInGraph.callProperty<Set<Any>>("tasksFromParent")
      assertThat(tasks).isNotNull()
      assertThat(tasks.map { it.javaClass.name })
        .containsExactly("test.TaskImpl1", "test.TaskImpl2")
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
            .trimIndent()
        )
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
            .trimIndent()
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
            .trimIndent()
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
          .trimIndent()
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

  @Test
  fun `graph extends interface`() {
    compile(
      source(
        """
        abstract class ChildScope

        interface Test

        @GraphExtension(ChildScope::class)
        interface ChildGraph : Test {
          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph : Test
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `graph extends interface - bound in child`() {
    compile(
      source(
        """
        abstract class ChildScope

        interface Test

        @GraphExtension(ChildScope::class)
        interface ChildGraph : Test {

          val test: Test
          @Binds val ChildGraph.bind: Test

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph : Test {
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `graph extends interface - bound in parent`() {
    compile(
      source(
        """
        abstract class Parent

        interface Test

        @GraphExtension(Parent::class)
        interface ChildGraph : Test {

          val test: Test

          @GraphExtension.Factory @ContributesTo(AppScope::class)
          interface Factory {
            fun create(): ChildGraph
          }
        }

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph : Test {
          @Binds val ExampleGraph.bind: Test
        }
        """
          .trimIndent()
      )
    )
  }

  @Test
  fun `multiple levels of provider inheritance`() {
    compile(
      source(
        """
        sealed interface GrandParentScope
        sealed interface ParentScope
        sealed interface ChildScope

        @ContributesTo(GrandParentScope::class)
        interface TestContribution {
            @Provides
            fun provideString(): String = ""
        }

        @SingleIn(GrandParentScope::class)
        @DependencyGraph(scope = GrandParentScope::class)
        interface GrandParentGraph

        @GraphExtension(ParentScope::class)
        interface ParentGraph {
            @GraphExtension.Factory @ContributesTo(GrandParentScope::class)
            interface Factory {
                fun createParentGraph(): ParentGraph
            }
        }

        @GraphExtension(ChildScope::class)
        interface ChildGraph {
            val string: String

            @GraphExtension.Factory @ContributesTo(ParentScope::class)
            interface Factory {
                fun createChildGraph(): ChildGraph
            }
        }
        """
          .trimIndent()
      )
    )
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/377#issuecomment-2878782694
  @Test
  fun `contributed graph should ensure scoping of class-injected types`() {
    compile(
      source(
        """
        sealed interface LoggedInScope

        @Inject @SingleIn(AppScope::class) class Dependency
        @Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val dependency: Dependency
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
            fun inject(screen: LoggedInScreen)

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }

        class LoggedInScreen {
            @Inject lateinit var childDependency: ChildDependency
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val loggedInScreen1 = classLoader.loadClass("test.LoggedInScreen").newInstanceStrict()
      loggedInGraph.callFunction<Any>("inject", loggedInScreen1)
      val childDep1 = loggedInScreen1.callProperty<Any>("childDependency")
      assertThat(childDep1).isNotNull()

      val loggedInScreen2 = classLoader.loadClass("test.LoggedInScreen").newInstanceStrict()
      loggedInGraph.callFunction<Any>("inject", loggedInScreen2)
      val childDep2 = loggedInScreen2.callProperty<Any>("childDependency")
      assertThat(childDep2).isNotNull()

      assertThat(childDep2).isSameInstanceAs(childDep1)
    }
  }

  /**
   * A more complex version of
   * [DependencyGraphTransformerTest.`roots already in the graph are not re-added`].
   */
  @Test
  fun `multiple contributed graphs should be able to inject dependency from parent`() {
    compile(
      source(
        """
        sealed interface LoggedInScope

        @Inject @SingleIn(AppScope::class) class Dependency

        @DependencyGraph(scope = AppScope::class)
        interface ExampleGraph {
          val dependency: Dependency
        }

        @GraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
            val dependency: Dependency

            @GraphExtension.Factory @ContributesTo(AppScope::class)
            interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
            }
        }
        """
          .trimIndent()
      )
    )
  }

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
        )
      )

    compile(
      source(
        """
        @DependencyGraph(AppScope::class)
        interface MainGraph
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
      previousCompilationResult = firstCompilation,
    ) {
      assertDiagnostics(
        """
        e: MainGraph.kt [Metro/EmptyMultibinding] Multibinding 'kotlin.collections.Map<kotlin.reflect.KClass<*>, kotlin.Any>' was unexpectedly empty.

        If you expect this multibinding to possibly be empty, annotate its declaration with `@Multibinds(allowEmpty = true)`.
        """
          .trimIndent()
      )
    }
  }
}
