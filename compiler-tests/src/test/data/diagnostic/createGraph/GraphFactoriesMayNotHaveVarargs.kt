// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides <!GRAPH_CREATORS_VARARG_ERROR!>vararg<!> args: String): AppGraph
  }
}

@GraphExtension
interface UnitGraph {
  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides <!GRAPH_CREATORS_VARARG_ERROR!>vararg<!> args: String): UnitGraph
  }
}

