// RENDER_DIAGNOSTICS_FULL_TEXT
@GraphExtension
interface LoggedInGraph {
  val int: Int

  @GraphExtension.Factory
  interface Factory {
    fun create(): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory {
  @Provides fun provideInt(): Int = 3

  // Illegal even though AppGraph implements _a_ factory, just not this one
  fun <!DEPENDENCY_GRAPH_ERROR!>loggedInGraph<!>(): LoggedInGraph

  // Legal
  fun loggedInGraphFactory(): LoggedInGraph.Factory

  override fun create(): LoggedInGraph
}
