// RENDER_DIAGNOSTICS_FULL_TEXT

package test

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  abstract class <!GRAPH_CREATORS_ERROR!>Factory<!> {
    abstract fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}
