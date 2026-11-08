// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  val value: Int

  @DependencyGraph.Factory
  abstract class Factory {
    abstract fun create(@Provides value: Int): AppGraph
  }

  companion object {
    fun <!DEPENDENCY_GRAPH_ERROR!>factory<!>(): Factory = TODO()
  }
}
