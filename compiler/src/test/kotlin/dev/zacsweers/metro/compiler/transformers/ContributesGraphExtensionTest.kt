package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.addPreviousResultToClasspath
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedMetroGraphClass
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

    val compilation =
      prepareCompilation(
          source(
            """
          @DependencyGraph(scope = AppScope::class, isExtendable = true)
          interface ExampleGraph {
            @Provides fun provideInt(): Int = 0
          }
        """
              .trimIndent()
          ),
          debug = true,
        )
        .apply {
          addPreviousResultToClasspath(loggedInScope)
          addPreviousResultToClasspath(loggedInGraph)
          addPreviousResultToClasspath(contributor)
        }

    val result = compilation.compile()
    with(result) {
      assertThat(exitCode).isEqualTo(KotlinCompilation.ExitCode.OK)
      val exampleGraph = ExampleGraph.generatedMetroGraphClass().createGraphWithNoArgs()
      val loggedInGraph = exampleGraph.callFunction<Any>("createLoggedInGraph")
      assertThat(loggedInGraph.callProperty<String>("string")).isEqualTo("0")
    }
  }
}
