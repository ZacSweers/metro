package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
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
}
