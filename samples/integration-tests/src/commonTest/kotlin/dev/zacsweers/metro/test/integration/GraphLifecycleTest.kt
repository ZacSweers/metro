// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:OptIn(dev.zacsweers.metro.ExperimentalMetroApi::class)

package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.CloseableGraph
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.OnGraphClose
import dev.zacsweers.metro.OnGraphInit
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphLifecycleTest {
  @Test
  fun childCleanupFinishesBeforeParentCleanup() {
    val database = Database()
    val graph = createGraphFactory<ApplicationGraph.Factory>().create(database)
    graph.session
    graph.close()
    graph.close()
    assertEquals(listOf("open", "session finished", "close"), database.events)
  }

  class Database {
    val events = mutableListOf<String>()

    fun open() {
      events += "open"
    }

    fun close() {
      events += "close"
    }
  }

  @DependencyGraph
  interface ApplicationGraph : CloseableGraph {
    val session: SessionGraph

    @OnGraphInit
    fun initialize(database: Database) {
      database.open()
    }

    @OnGraphClose
    fun shutdown(database: Database) {
      database.close()
    }

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides database: Database): ApplicationGraph
    }
  }

  @GraphExtension
  interface SessionGraph : CloseableGraph {
    @OnGraphClose
    fun shutdown(database: Database) {
      database.events += "session finished"
    }
  }
}
