package dev.zacsweers.lattice.sample

import dagger.Component
import dev.zacsweers.lattice.DependencyGraph
import dev.zacsweers.lattice.createGraphFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Basic tests having dagger components and lattice graphs depend on each other. */
class DaggerDependenciesTest {

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
        .create(DaggerStringComponent.factory().create("Hello, world!"))
    assertEquals("Hello, world!", graph.message)
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
