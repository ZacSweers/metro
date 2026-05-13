// RENDER_DIAGNOSTICS_FULL_TEXT
@GraphExtension
interface LoggedInGraph {
  @GraphExtension.Factory
  interface Factory {
    fun create(@Provides value: Long): LoggedInGraph
  }
}

// Not annotated with @GraphExtension.Factory
interface AdhocFactory {
  fun create(@Provides value: Long): LoggedInGraph
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory, AdhocFactory {
  // Ok - override from @GraphExtension.Factory
  override fun create(@Provides value: Long): LoggedInGraph

  // Bad - directly declared ad-hoc graph extension factory
  fun <!ADHOC_GRAPH_EXTENSION_FACTORY!>createLoggedIn<!>(@Provides param: Long): LoggedInGraph
}

@DependencyGraph
interface AppGraph2 : AdhocFactory {
  // Bad - override from non-@GraphExtension.Factory interface
  override fun <!ADHOC_GRAPH_EXTENSION_FACTORY!>create<!>(@Provides value: Long): LoggedInGraph
}

@DependencyGraph
interface <!ADHOC_GRAPH_EXTENSION_FACTORY!>AppGraph3<!> : AdhocFactory {
  // Bad - inherits but undeclared from non-@GraphExtension.Factory interface
}
