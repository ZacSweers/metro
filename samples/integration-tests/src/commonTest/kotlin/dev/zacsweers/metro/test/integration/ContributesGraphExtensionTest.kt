// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.ContributesGraphExtension
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.asContribution
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class ContributesGraphExtensionTest {
  @Test
  fun simple() {
    val exampleGraph = createGraph<SimpleScenario.ExampleGraph>()
    val loggedInGraph =
      exampleGraph.asContribution<SimpleScenario.LoggedInGraph.Factory>().createLoggedInGraph()
    val int = loggedInGraph.int
    assertEquals(int, 0)
  }

  object SimpleScenario {
    abstract class SimpleApp

    abstract class SimpleLoggedInScope

    @ContributesGraphExtension(SimpleLoggedInScope::class)
    interface LoggedInGraph {
      val int: Int

      @ContributesGraphExtension.Factory(SimpleApp::class)
      interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
      }
    }

    @DependencyGraph(scope = SimpleApp::class, isExtendable = true)
    interface ExampleGraph {
      @Provides fun provideInt(): Int = 0
    }
  }
}
