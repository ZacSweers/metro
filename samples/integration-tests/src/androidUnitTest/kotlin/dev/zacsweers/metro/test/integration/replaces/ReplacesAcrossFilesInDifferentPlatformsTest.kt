// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.replaces

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertEquals

class ReplacesAcrossFilesInDifferentPlatformsTest {
  @Test
  fun `fake implementations replace real ones across different files`() {
    val graph = createGraph<TestGraph>()

    assertEquals("android", graph.platform.platformName)
  }

  @DependencyGraph(AppScope::class)
  interface TestGraph {
    val platform: Platform
  }
}
