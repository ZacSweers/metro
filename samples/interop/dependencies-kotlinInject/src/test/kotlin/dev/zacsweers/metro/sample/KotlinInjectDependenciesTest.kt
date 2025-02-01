// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample

import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import me.tatarka.inject.annotations.Component

/** Basic tests having kotlin-inject components and metro graphs depend on each other. */
class KotlinInjectDependenciesTest {

  @DependencyGraph
  interface GraphDependingOnComponent {
    val message: String

    @DependencyGraph.Factory
    interface Factory {
      fun create(stringComponent: StringComponent): GraphDependingOnComponent
    }
  }

  @Test
  fun testGraphDependingOnComponent() {
    val graph =
      createGraphFactory<GraphDependingOnComponent.Factory>()
        .create(StringComponent::class.create("Hello, world!"))
    assertEquals("Hello, world!", graph.message)
  }

  @Component
  abstract class ComponentDependingOnGraph(@Component val stringGraph: StringGraph) {
    abstract val message: String
  }

  @Test
  fun testComponentDependingOnGraph() {
    val component =
      ComponentDependingOnGraph::class.create(
        createGraphFactory<StringGraph.Factory>().create("Hello, world!")
      )
    assertEquals("Hello, world!", component.message)
  }
}
