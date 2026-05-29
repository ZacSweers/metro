// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.sample.lib.UpstreamEntryPoint
import dev.zacsweers.metro.sample.lib.UpstreamMessage
import javax.inject.Singleton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies upstream Hilt modules and entry points flow into a downstream Metro graph. */
class HiltMultiModuleTest {
  @DependencyGraph(Singleton::class) interface AppGraph

  @Test
  fun upstreamHiltModuleAndEntryPointFlowAcrossModules() {
    val graph = createGraph<AppGraph>()
    assertTrue(graph is UpstreamEntryPoint)
    val entryPoint = graph as UpstreamEntryPoint
    assertEquals(UpstreamMessage("Hello from Hilt"), entryPoint.upstreamMessage)
  }
}
