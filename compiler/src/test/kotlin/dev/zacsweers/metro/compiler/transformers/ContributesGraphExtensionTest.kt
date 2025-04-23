// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
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
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("0")
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
      ),
      debug = true,
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

  // TODO
  //  - multiple scopes to same graph. Need disambiguating names
  //  Checkers
  //  - abstract classes not allowed
  //  - contributed factories must be nested classes of contributed graph
}
