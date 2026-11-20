// RENDER_DIAGNOSTICS_FULL_TEXT

package test

abstract class LoggedInScope

<!GRAPH_CREATORS_ERROR!>@DependencyGraph(scope = AppScope::class)<!>
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
  @GraphExtension.Factory @ContributesTo(LoggedInScope::class)
  interface Factory {
    fun createExampleGraph(): ExampleGraph
  }
}
