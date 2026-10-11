// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMetroApi::class)

package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.CloseableGraph
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.ExperimentalMetroApi
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.OnGraphClose
import dev.zacsweers.metro.OnGraphInit
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GraphLifecycleTest {
  @Test
  fun initRunsOnCreationAndCloseRunsOnce() {
    val graph = createGraph<LifecycleScenario.AppGraph>()
    assertEquals(listOf("app init"), graph.log.events)

    graph.close()
    graph.close()
    assertEquals(listOf("app init", "app close"), graph.log.events)
  }

  @Test
  fun closingAGraphClosesItsExtensionsFirst() {
    val graph = createGraph<LifecycleScenario.AppGraph>()
    graph.child

    graph.close()
    assertEquals(listOf("app init", "child close", "app close"), graph.log.events)
  }

  @Test
  fun extensionsClosedOnTheirOwnAreNotClosedAgain() {
    val graph = createGraph<LifecycleScenario.AppGraph>()
    graph.child.close()

    graph.close()
    assertEquals(listOf("app init", "child close", "app close"), graph.log.events)
  }

  @Test
  fun closedGraphsCannotCreateExtensions() {
    val graph = createGraph<LifecycleScenario.AppGraph>()
    graph.close()

    assertFailsWith<IllegalStateException> { graph.child }
  }

  object LifecycleScenario {
    abstract class LifecycleScope private constructor()

    @SingleIn(LifecycleScope::class)
    @Inject
    class EventLog {
      val events = mutableListOf<String>()
    }

    @GraphExtension
    interface ChildGraph : CloseableGraph {
      @OnGraphClose
      fun stop(log: EventLog) {
        log.events += "child close"
      }
    }

    @DependencyGraph(LifecycleScope::class)
    interface AppGraph : CloseableGraph {
      val log: EventLog
      val child: ChildGraph

      @OnGraphInit
      fun start(log: EventLog) {
        log.events += "app init"
      }

      @OnGraphClose
      fun stop(log: EventLog) {
        log.events += "app close"
      }
    }
  }
}
