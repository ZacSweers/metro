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
  class Connection {
    val events = mutableListOf<String>()

    fun open() {
      events += "open"
    }

    fun close() {
      events += "close"
    }
  }

  @GraphExtension
  interface Session : CloseableGraph {
    @OnGraphInit
    fun start(connection: Connection) {
      connection.open()
    }

    @OnGraphClose
    fun stop(connection: Connection) {
      connection.close()
    }
  }

  @DependencyGraph
  interface Application : CloseableGraph {
    val session: Session

    @OnGraphClose
    fun stop(connection: Connection) {
      connection.events += "shutdown"
    }

    @DependencyGraph.Factory
    fun interface Factory {
      fun create(@Provides connection: Connection): Application
    }
  }

  @Test
  fun closingTheApplicationClosesItsSessions() {
    val connection = Connection()
    val application = createGraphFactory<Application.Factory>().create(connection)
    application.session
    application.close()
    assertEquals(listOf("open", "close", "shutdown"), connection.events)
  }
}
