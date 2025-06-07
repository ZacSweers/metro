package dev.zacsweers.metro.test.integration.replaces

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Tests for same-compilation replacements, which don't seem to be reproducible in the compiler test
 * framework.
 */
class ReplacementsTest {
  @Test
  fun contributesBinding() {
    val graph = createGraph<TestGraph>()
    assertIs<TestCache>(graph.cache)
  }

  @DependencyGraph(AppScope::class)
  interface TestGraph {
    val cache: Cache
  }
}
