abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph
