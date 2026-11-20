// RENDER_DIAGNOSTICS_FULL_TEXT

package test

abstract class LoggedInScope

<!GRAPH_CREATORS_ERROR!>@GraphExtension(LoggedInScope::class)<!>
interface LoggedInGraph {
  val int: Int
}

interface SomewhereElse {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}
