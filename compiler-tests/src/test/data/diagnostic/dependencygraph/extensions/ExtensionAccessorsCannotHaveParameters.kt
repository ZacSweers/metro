// RENDER_DIAGNOSTICS_FULL_TEXT
@GraphExtension
interface LoggedInGraph {
  val int: Int
}

@DependencyGraph
interface AppGraph {
  @Provides fun provideInt(): Int = 3

  // Legal
  fun loggedInGraphFactory(): LoggedInGraph

  // Illegal
  fun loggedInGraphFactory(<!DEPENDENCY_GRAPH_ERROR!>param1<!>: Long, <!DEPENDENCY_GRAPH_ERROR!>param2<!>: Int): LoggedInGraph

  fun <!DEPENDENCY_GRAPH_ERROR!>String<!>.loggedInGraphFactory(): LoggedInGraph

  <!UNSUPPORTED_FEATURE!>context(<!DEPENDENCY_GRAPH_ERROR!>int<!>: Int)<!>
  fun loggedInGraphFactory(): LoggedInGraph
}
