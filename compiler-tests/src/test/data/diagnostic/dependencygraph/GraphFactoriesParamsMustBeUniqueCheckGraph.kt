// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
interface ExampleGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes intGraph: IntGraph, @Includes <!GRAPH_CREATORS_ERROR!>intGraph2<!>: IntGraph): ExampleGraph
  }
}
@DependencyGraph
interface IntGraph {
  val value: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Int): IntGraph
  }
}
