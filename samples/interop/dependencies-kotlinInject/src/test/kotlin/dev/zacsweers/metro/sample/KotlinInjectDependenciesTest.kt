/*
 * Copyright (C) 2025 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
