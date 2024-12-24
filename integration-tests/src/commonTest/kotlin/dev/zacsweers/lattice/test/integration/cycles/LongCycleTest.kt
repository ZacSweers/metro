package dev.zacsweers.lattice.test.integration.cycles

import dev.zacsweers.lattice.createGraph
import kotlin.test.Test

class LongCycleTest {
  @Test
  fun testLongCycle() {
    val graph = createGraph<LongCycle.LongCycleGraph>()
    val class1 = graph.class1
  }
}