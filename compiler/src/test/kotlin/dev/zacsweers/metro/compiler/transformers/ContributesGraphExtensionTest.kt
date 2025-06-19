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
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
import dev.zacsweers.metro.compiler.newInstanceStrict
import kotlin.test.assertNotNull
import org.junit.Test

class ContributesGraphExtensionTest : MetroCompilerTest() {
  @Test
  fun simple() {
    compile(
      source(
        """
          abstract class LoggedInScope

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val int: Int

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          abstract class LoggedInGraph {
            abstract val int: Int

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val int: Int

            @ContributesGraphExtension.Factory(AppScope::class)
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
          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      ),
      previousCompilationResult = firstCompilation,
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
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
          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @ContributesTo(LoggedInScope::class)
          interface LoggedInStringProvider {
            @Provides fun provideString(int: Int): String = int.toString()
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val contributedInterface: ContributedInterface

            @ContributesGraphExtension.Factory(AppScope::class)
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
          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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


          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val contributedInterface: ContributedInterface

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val consumer: ConsumerInterface

            @ContributesGraphExtension.Factory(AppScope::class)
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            val contributions: Set<ContributedInterface>
          }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
              // Important for @Multibinding to be used for this test's coverage, as opposed to @ElementsIntoSet
              @Multibinds(allowEmpty = true)
              fun provideMulti(): Set<@JvmSuppressWildcards MultiboundType>
            }

            @ContributesGraphExtension(LoggedInScope::class)
            interface LoggedInGraph {
              val multi: Set<MultiboundType>

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                fun createLoggedInGraph(): LoggedInGraph
              }
            }

            @DependencyGraph(AppScope::class, isExtendable = true)
            interface ExampleGraph
          """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          interface GraphExtension<T> {
            val value: T
          }

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph : GraphExtension<Int> {

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val int: Int

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory : GraphExtensionFactory<LoggedInGraph>
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(@Provides long: Long): LoggedInGraph
            }
          }

          @ContributesTo(LoggedInScope::class)
          interface LoggedInStringProvider {
            @Provides
            fun provideString(int: Int, long: Long): String = (int + long).toString()
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(@Provides @Named("long") long: Long): LoggedInGraph
            }
          }

          @ContributesTo(LoggedInScope::class)
          interface LoggedInStringProvider {
            @Provides
            fun provideString(int: Int, @Named("long") long: Long): String = (int + long).toString()
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(@Includes stringProvider: StringProvider): LoggedInGraph
            }
          }

          class StringProvider(val value: String = "Hello")

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val string: String

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(@Extends stringGraph: StringGraph): LoggedInGraph
            }
          }

          @DependencyGraph(scope = Unit::class, isExtendable = true)
          interface StringGraph {
            val string: String
            @DependencyGraph.Factory
            interface Factory {
              fun create(@Provides string: String): StringGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val stringGraphClass = classLoader.loadClass("test.StringGraph")
      val stringGraph = stringGraphClass.generatedMetroGraphClass().createGraphViaFactory("Hello")
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph", stringGraph)
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("Hello")
    }
  }

  @Test
  fun `contributed graph factories can be excluded`() {
    compile(
      source(
        """
          abstract class LoggedInScope

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(
            scope = AppScope::class,
            isExtendable = true,
            excludes = [LoggedInGraph.Factory::class]
          )
          interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      assertNotNull(ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs())
      // Assert no $$ContributedLoggedInGraph or createLoggedInGraph method or parent interface
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(
            scope = AppScope::class,
            isExtendable = true,
            excludes = [LoggedInGraph::class]
          )
          interface ExampleGraph
        """
          .trimIndent()
      )
    ) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      assertNotNull(ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs())
      // Assert no $$ContributedLoggedInGraph or createLoggedInGraph method or parent interface
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

        @ContributesGraphExtension(ProfileScope::class)
        interface ProfileGraph {
          val string: String

          @ContributesGraphExtension.Factory(LoggedInScope::class)
          interface Factory {
            fun createProfileGraph(): ProfileGraph
          }
        }

        @ContributesGraphExtension(LoggedInScope::class, isExtendable = true)
        interface LoggedInGraph {
          val int: Int

          @Provides fun provideString(int: Int): String = int.toString()

          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
        }
      """
          .trimIndent()
      )
    ) {
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<Int>("int")).isEqualTo(0)

      val profileGraph = loggedInGraph.callFunction<Any>("createProfileGraph")
      assertThat(profileGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }

  @Test
  fun `chained contributed graphs must be extendable`() {
    compile(
      source(
        """
        abstract class LoggedInScope
        abstract class ProfileScope

        @ContributesGraphExtension(ProfileScope::class)
        interface ProfileGraph {
          val string: String

          @ContributesGraphExtension.Factory(LoggedInScope::class)
          interface Factory {
            fun createProfileGraph(): ProfileGraph
          }
        }

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @Provides fun provideString(int: Int): String = int.toString()

          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          e: LoggedInScope.kt:9:1 Contributed graph extension 'test.ProfileGraph' contributes to parent graph 'test.LoggedInGraph' (scope 'test.LoggedInScope'), but LoggedInGraph is not extendable.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed graph target must be extendable`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @ContributesGraphExtension.Factory(AppScope::class)
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
          e: LoggedInScope.kt:8:1 Contributed graph extension 'test.LoggedInGraph' contributes to parent graph 'test.ExampleGraph' (scope 'dev.zacsweers.metro.AppScope'), but ExampleGraph is not extendable.

          Either mark ExampleGraph as extendable (`@DependencyGraph(isExtendable = true)`), or exclude it from ExampleGraph (`@DependencyGraph(excludes = [LoggedInGraph::class])`).
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed graph factories must be interfaces`() {
    compile(
      source(
        """
        abstract class LoggedInScope

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

          @ContributesGraphExtension.Factory(AppScope::class)
          abstract class Factory {
            abstract fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          e: LoggedInScope.kt:13:18 ContributesGraphExtension.Factory declarations can only be interfaces.
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

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int

        }
        @ContributesGraphExtension.Factory(AppScope::class)
        interface Factory {
          fun createLoggedInGraph(): LoggedInGraph
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          e: LoggedInScope.kt:8:1 ContributesGraphExtension.Factory declarations must be nested within the contributed graph they create but was top-level.
          e: LoggedInScope.kt:9:11 @ContributesGraphExtension declarations must have a nested class annotated with @ContributesGraphExtension.Factory.
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

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int
        }

        interface SomewhereElse {
          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          e: LoggedInScope.kt:8:1 ContributesGraphExtension.Factory declarations must be nested within the contributed graph they create but was test.SomewhereElse.
          e: LoggedInScope.kt:9:11 @ContributesGraphExtension declarations must have a nested class annotated with @ContributesGraphExtension.Factory.
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

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
        interface ExampleGraph {
          @Provides fun provideInt(): Int = 0
          @ContributesGraphExtension.Factory(LoggedInScope::class)
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
          e: LoggedInScope.kt:8:1 ContributesGraphExtension.Factory abstract function 'createExampleGraph' must return a contributed graph extension but found test.ExampleGraph.
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

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val int: Int
          @ContributesGraphExtension.Factory(LoggedInScope::class)
          interface Factory {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }

        @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          e: LoggedInScope.kt:11:3 ContributesGraphExtension.Factory declarations must contribute to a different scope than their contributed graph. However, this factory and its contributed graph both contribute to 'test.LoggedInScope'.
        """
          .trimIndent()
      )
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/359
  @Test
  fun `ContributesGraphExtension can access providers from interface contributed to parent`() {
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
          @DependencyGraph(scope = TestScope::class, isExtendable = true)
          interface ParentGraph

          @ContributesGraphExtension(TestChildScope::class)
          interface ChildGraph {
              val string: String

              @ContributesGraphExtension.Factory(TestScope::class)
              interface Factory {
                  fun createChildGraph(): ChildGraph
              }
          }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ParentGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createChildGraph")
      assertThat(childGraph.callProperty<String>("string")).isEqualTo("")
    }
  }

  // https://github.com/ZacSweers/metro/issues/377
  @Test
  fun `suggest adding to parent if scoped constructor-injected class matches parent scope but isn't provided`() {
    compile(
      source(
        """
          sealed interface LoggedInScope

          @Inject @SingleIn(AppScope::class) class Dependency
          @Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            // Works if added explicitly like this
            // val dependency: Dependency
          }

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
              val childDependency: ChildDependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
        """
          .trimIndent()
      ),
      options = metroOptions.copy(enableScopedInjectClassHints = false),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        $$$"""
          e: LoggedInScope.kt [Metro/IncompatiblyScopedBindings] test.ExampleGraph.$$ContributedLoggedInGraph (scopes '@SingleIn(LoggedInScope::class)') may not reference bindings from different scopes:
              test.Dependency (scoped to '@SingleIn(AppScope::class)')
              test.Dependency is injected at
                  [test.ExampleGraph.$$ContributedLoggedInGraph] test.ChildDependency(…, dep)
              test.ChildDependency is requested at
                  [test.ExampleGraph.$$ContributedLoggedInGraph] test.LoggedInGraph#childDependency


          (Hint)
          It appears that extended parent graph 'test.ExampleGraph' does declare the '@SingleIn(AppScope::class)' scope but doesn't use 'Dependency' directly.
          To work around this, consider declaring an accessor for 'Dependency' in that graph (i.e. `val dependency: Dependency`) or enabling the `enableScopedInjectClassHints` option.
          See https://github.com/ZacSweers/metro/issues/377 for more details.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `contributed graph with qualified provider`() {
    compile(
      source(
        """
          abstract class Parent

          @ContributesGraphExtension(Parent::class, isExtendable = true)
          interface ParentGraph {
            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun create(
                  @Provides @ForScope(Parent::class) string: String
              ): ParentGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: ChildDependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val childGraph = parentGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(childGraph.callProperty<Any>("childDependency")).isNotNull()
    }
  }

  @Test
  fun `a scoped @ContributesBinding class can be accessed in a child graph without an explicit accessor when associated hints are enabled`() {
    compile(
      source(
        """
          sealed interface LoggedInScope
          interface Bob

          @Inject
          @SingleIn(AppScope::class)
          @ContributesBinding(AppScope::class)
          class Dependency : Bob

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Bob

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
          @DependencyGraph(AppScope::class, isExtendable = true)
          interface ExampleGraph

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Dependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Bob

              @ContributesGraphExtension.Factory(AppScope::class)
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
          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      compilationBlock = {
        addPreviousResultToClasspath(injectDepCompilation)
        addPreviousResultToClasspath(graphExtensionCompilation)
      },
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Dependency

              @ContributesGraphExtension.Factory(AppScope::class)
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
          @DependencyGraph(AppScope::class, isExtendable = true)
          interface ExampleGraph
        """
          .trimIndent()
      ),
      compilationBlock = {
        addPreviousResultToClasspath(injectDepCompilation)
        addPreviousResultToClasspath(graphExtensionCompilation)
      },
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val childDependency: Dependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }

          @ContributesGraphExtension(OtherScope::class)
          interface OtherGraph {
            val childDependency: Dependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createOtherGraph(): OtherGraph
              }
          }
        """
          .trimIndent()
      )
    ) {
      val parentGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
  fun `ContributesGraphExtension can provide multibindings`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        @DependencyGraph(AppScope::class, isExtendable = true)
        interface ExampleGraph

        @ContributesGraphExtension(LoggedInScope::class, isExtendable = true)
        interface LoggedInGraph {
          val ints: Set<Int>
          @Provides @IntoSet fun provideInt1(): Int = 1
          @Provides @IntoSet fun provideInt2(): Int = 2
          @Provides
          @ElementsIntoSet
          fun provideInts(): Set<Int> = setOf(3, 4)

          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
      """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val ints = loggedInGraph.callProperty<Set<Int>>("ints")
      assertThat(ints).isNotNull()
      assertThat(ints).containsExactly(1, 2, 3, 4)
    }
  }

  @Test
  fun `ContributesGraphExtension can provide multibindings via @Binds`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        interface Task
        @Inject class TaskImpl1 : Task
        @Inject class TaskImpl2 : Task

        @DependencyGraph(AppScope::class, isExtendable = true)
        interface ExampleGraph

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val tasks: Set<Task>
          @IntoSet @Binds val TaskImpl1.bind: Task
          @IntoSet @Binds val TaskImpl2.bind: Task

          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
      """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val loggedInGraph = graph.callFunction<Any>("createLoggedInGraph")
      val tasks = loggedInGraph.callProperty<Set<Any>>("tasks")
      assertThat(tasks).isNotNull()
      assertThat(tasks.map { it.javaClass.name })
        .containsExactly("test.TaskImpl1", "test.TaskImpl2")
    }
  }

  @Test
  fun `ContributesGraphExtension can access multibindings provided by its parent`() {
    compile(
      source(
        """
        object AppScope
        object LoggedInScope

        interface Task
        @Inject class TaskImpl1 : Task
        @Inject class TaskImpl2 : Task

        @DependencyGraph(AppScope::class, isExtendable = true)
        interface ExampleGraph {
          val tasks: Set<Task>
          @IntoSet @Binds val TaskImpl1.bind: Task
          @IntoSet @Binds val TaskImpl2.bind: Task
        }

        @ContributesGraphExtension(LoggedInScope::class)
        interface LoggedInGraph {
          val tasksFromParent: Set<Task>

          @ContributesGraphExtension.Factory(AppScope::class)
          interface Factory1 {
            fun createLoggedInGraph(): LoggedInGraph
          }
        }
      """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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
          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
            val contributedInterface: ContributedInterface
            val impl1: Impl1

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun createLoggedInGraph(): LoggedInGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @ContributesGraphExtension(ChildScope::class, isExtendable = true)
          interface ChildGraph : Test {
            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun create(): ChildGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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

          @ContributesGraphExtension(ChildScope::class, isExtendable = true)
          interface ChildGraph : Test {

            val test: Test
            @Binds val ChildGraph.bind: Test

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun create(): ChildGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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

          @ContributesGraphExtension(Parent::class, isExtendable = true)
          interface ChildGraph : Test {

            val test: Test

            @ContributesGraphExtension.Factory(AppScope::class)
            interface Factory {
              fun create(): ChildGraph
            }
          }

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
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
          @DependencyGraph(scope = GrandParentScope::class, isExtendable = true)
          interface GrandParentGraph

          @ContributesGraphExtension(ParentScope::class, isExtendable = true)
          interface ParentGraph {
              @ContributesGraphExtension.Factory(GrandParentScope::class)
              interface Factory {
                  fun createParentGraph(): ParentGraph
              }
          }

          @ContributesGraphExtension(ChildScope::class)
          interface ChildGraph {
              val string: String

              @ContributesGraphExtension.Factory(ParentScope::class)
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            val dependency: Dependency
          }

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
              fun inject(screen: LoggedInScreen)

              @ContributesGraphExtension.Factory(AppScope::class)
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
      val graph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
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

          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            val dependency: Dependency
          }

          @ContributesGraphExtension(LoggedInScope::class)
          interface LoggedInGraph {
              val dependency: Dependency

              @ContributesGraphExtension.Factory(AppScope::class)
              interface Factory {
                  fun createLoggedInGraph(): LoggedInGraph
              }
          }
        """
          .trimIndent()
      )
    )
  }
}
