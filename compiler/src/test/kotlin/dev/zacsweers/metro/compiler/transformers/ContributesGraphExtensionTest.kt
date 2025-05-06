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
          e: LoggedInScope.kt:9:1 Contributed graph extension 'test.ProfileGraph' contributes to parent graph 'test.LoggedInGraph' (scope 'test.LoggedInScope') but LoggedInGraph is not extendable.
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
          e: LoggedInScope.kt:8:1 Contributed graph extension 'test.LoggedInGraph' contributes to parent graph 'test.ExampleGraph' (scope 'dev.zacsweers.metro.AppScope') but ExampleGraph is not extendable.

          Either mark ExampleGraph as extendable (`@DependencyGraph(isExtendable = true)`) or exclude it from ExampleGraph (`@DependencyGraph(excludes = [LoggedInGraph::class])`)
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
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
          e: LoggedInScope.kt [Metro/IncompatiblyScopedBindings] test.ExampleGraph.$${'$'}ContributedLoggedInGraph (scopes '@SingleIn(LoggedInScope::class)') may not reference bindings from different scopes:
              test.Dependency (scoped to '@SingleIn(AppScope::class)')
              test.Dependency is injected at
                  [test.ExampleGraph.$${'$'}ContributedLoggedInGraph] test.ChildDependency(…, dep)
              test.ChildDependency is requested at
                  [test.ExampleGraph.$${'$'}ContributedLoggedInGraph] test.LoggedInGraph#childDependency


          (Hint)
          It appears that extended parent graph 'test.ExampleGraph' does declare the '@SingleIn(AppScope::class)' scope but doesn't use 'Dependency' directly.
          To work around this, consider declaring an accessor for 'Dependency' in that graph (i.e. `val dependency: Dependency`).
          See https://github.com/ZacSweers/metro/issues/377 for more details.
        """
          .trimIndent()
      )
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

  // TODO
  //  - multiple scopes to same graph. Need disambiguating names
}
