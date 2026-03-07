// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.test.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph
import dev.zacsweers.metro.test.integration.android.databinding.ActivityMainBinding
import kotlin.test.Test
import kotlin.test.assertNotNull

/*
Compilation-only regression test for https://github.com/ZacSweers/metro/pull/462
*/

interface BaseFactory<B, T> {
  fun create(binding: B): T
}

@DependencyGraph(AppScope::class) interface AppGraph

@ContributesTo(AppScope::class)
interface TestRendererGraph {
  val factory: TestRenderer.Factory
}

@AssistedInject
class TestRenderer(@Assisted private val binding: ActivityMainBinding) {
  @AssistedFactory abstract class Factory : BaseFactory<ActivityMainBinding, TestRenderer>
}

class ViewBindingIntegrationTest {
  @Test
  fun graphInit() {
    val graph = createGraph<AppGraph>()
    assertNotNull(graph.factory)
  }
}
