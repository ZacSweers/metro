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

import dagger.Component
import dev.zacsweers.metro.DependencyGraph
import kotlin.test.Test

/** Basic tests having dagger components and metro graphs depend on each other. */
class DaggerDependenciesTest {

  @DependencyGraph
  interface GraphDependingOnComponent {
    val message: String

    @DependencyGraph.Factory
    interface Factory {
      fun create(stringComponent: StringComponent): GraphDependingOnComponent
    }
  }

  // TODO KAPT/KSP are unreliable here, the generated code is not always linked :(
  @Test
  fun testGraphDependingOnComponent() {
    //    val graph =
    //      createGraphFactory<GraphDependingOnComponent.Factory>()
    //        .create(DaggerStringComponent.factory().create("Hello, world!"))
    //    assertEquals("Hello, world!", graph.message)
  }

  @Component(dependencies = [StringGraph::class])
  interface ComponentDependingOnGraph {
    val message: String

    @Component.Factory
    interface Factory {
      fun create(stringGraph: StringGraph): ComponentDependingOnGraph
    }
  }

  // TODO KAPT/KSP are unreliable here, the generated code is not always linked :(
  @Test
  fun testDaggerComponentDependingOnGraph() {
    //    val component =
    //      DaggerDaggerDependenciesTest_ComponentDependingOnGraph.factory()
    //        .create(createGraphFactory<StringGraph.Factory>().create("Hello, world!"))
    //    assertEquals("Hello, world!", component.message)
  }
}
