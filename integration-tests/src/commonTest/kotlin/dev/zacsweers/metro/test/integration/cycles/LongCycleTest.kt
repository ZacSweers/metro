// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.cycles

import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertNotNull

class LongCycleTest {
  @Test
  fun testLongCycle() {
    val graph = createGraph<LongCycle.LongCycleGraph>()
    assertNotNull(graph.class1)
  }
}
