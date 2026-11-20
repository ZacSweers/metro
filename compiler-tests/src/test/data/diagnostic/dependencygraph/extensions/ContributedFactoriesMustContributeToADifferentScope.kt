// RENDER_DIAGNOSTICS_FULL_TEXT

package test

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int
  <!GRAPH_CREATORS_ERROR!>@GraphExtension.Factory<!> @ContributesTo(LoggedInScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}
