// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.replaces

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplacesAcrossFilesTest {
  @Test
  fun `fake implementations replace real ones across different files`() {
    val graph = createGraph<TestGraph>()

    assertEquals("fake network response", graph.networkService.makeRequest())
    assertEquals("fake database data", graph.databaseService.query())
    assertEquals("fake config", graph.config)

    // Verify set multibinding replacement
    val features = graph.features
    assertEquals(setOf("fake-feature-1", "fake-feature-2", "fake-feature-3"), features)

    // Verify map multibinding replacement
    val handlers = graph.handlers
    assertEquals(3, handlers.size)
    assertEquals(
      mapOf(
        "fake-handler-1" to "fake-handler-1-impl",
        "fake-handler-2" to "fake-handler-2-impl",
        "fake-handler-3" to "fake-handler-3-impl",
      ),
      handlers,
    )
  }

  @DependencyGraph(AppScope::class)
  interface TestGraph {
    val networkService: NetworkService
    val databaseService: DatabaseService
    val config: String
    val features: Set<String>
    val handlers: Map<String, String>
  }
}
