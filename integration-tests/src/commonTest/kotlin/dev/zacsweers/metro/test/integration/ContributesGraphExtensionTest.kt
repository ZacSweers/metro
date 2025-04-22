package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesGraphExtension
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class ContributesGraphExtensionTest {
  @Test
  fun simple() {
    val exampleGraph = createGraph<ExampleGraph>()
    val loggedInGraph = exampleGraph.createLoggedInGraph()
    val int = loggedInGraph.int
    assertEquals(int, 0)
  }

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
    @Provides
    fun provideInt(): Int = 0
  }
}
