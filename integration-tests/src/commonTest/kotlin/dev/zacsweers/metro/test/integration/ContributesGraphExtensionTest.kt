// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import kotlin.test.Test

class ContributesGraphExtensionTest {
  @Test
  fun simple() {
    println("Not yet working on all platforms due to https://youtrack.jetbrains.com/issue/KT-76257")
    //    val exampleGraph = createGraph<ExampleGraph>()
    //    val loggedInGraph = exampleGraph.createLoggedInGraph()
    //    val int = loggedInGraph.int
    //    assertEquals(int, 0)
  }
  //
  //  abstract class LoggedInScope
  //
  //  @ContributesGraphExtension(LoggedInScope::class)
  //  interface LoggedInGraph {
  //    val int: Int
  //
  //    @ContributesGraphExtension.Factory(AppScope::class)
  //    interface Factory {
  //      fun createLoggedInGraph(): LoggedInGraph
  //    }
  //  }
  //
  //  @DependencyGraph(scope = AppScope::class, isExtendable = true)
  //  interface ExampleGraph {
  //    @Provides fun provideInt(): Int = 0
  //  }
}
