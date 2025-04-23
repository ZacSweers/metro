abstract class LoggedInScope

@ContributesGraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createLoggedInGraph()
  }
}

@DependencyGraph(scope = AppScope::class, isExtendable = true)
interface ExampleGraph
