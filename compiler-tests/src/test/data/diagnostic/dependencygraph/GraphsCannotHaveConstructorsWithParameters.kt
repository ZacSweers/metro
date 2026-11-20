// RENDER_DIAGNOSTICS_FULL_TEXT

package test

@DependencyGraph
abstract class ExampleGraph<!DEPENDENCY_GRAPH_ERROR!>(
  @get:Provides
  val text: String
)<!> {

  abstract fun string(): String

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides text: String): ExampleGraph
  }
}
