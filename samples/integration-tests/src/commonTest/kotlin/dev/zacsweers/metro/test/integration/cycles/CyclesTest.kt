// Copyright (C) 2015 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration.cycles

import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Extends
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Cycle classes used for testing cyclic dependencies.
 *
 * ```
 * A ← (E ← D ← B ← C ← Provider<A>, Lazy<A>), (B ← C ← Provider<A>, Lazy<A>)
 * S ← Provider<S>, Lazy<S>
 * ```
 */
class CyclesTest {

  @Test
  fun providerMapIndirectionCycle() {
    val cycleMapGraph = createGraph<CycleMapGraph>()
    assertNotNull(cycleMapGraph.y())
    assertContains(cycleMapGraph.y().mapOfProvidersOfX, "X")
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"])
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke())
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfX["X"]?.invoke()?.y)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfX.size, 1)
    assertContains(cycleMapGraph.y().mapOfProvidersOfY, "Y")
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"])
    assertNotNull(cycleMapGraph.y().mapOfProvidersOfY["Y"]?.invoke())
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfX.size, 1)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY["Y"]!!().mapOfProvidersOfY.size, 1)
    assertEquals(cycleMapGraph.y().mapOfProvidersOfY.size, 1)
  }

  @Inject class X(val y: Y)

  @Inject
  class Y(
    val mapOfProvidersOfX: Map<String, Provider<X>>,
    val mapOfProvidersOfY: Map<String, Provider<Y>>,
  )

  @DependencyGraph
  interface CycleMapGraph {
    fun y(): Y

    @Binds @IntoMap @StringKey("X") val X.x: X

    @Binds @IntoMap @StringKey("Y") val Y.y: Y
  }
}
