package dev.zacsweers.lattice.sample

import dev.zacsweers.lattice.BindsInstance
import dev.zacsweers.lattice.DependencyGraph

@DependencyGraph
interface StringGraph {
  val message: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@BindsInstance message: String): StringGraph
  }
}
