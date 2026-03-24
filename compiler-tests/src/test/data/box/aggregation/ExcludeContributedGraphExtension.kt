object LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  // No binding provided.
  val userId: Int

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@GraphExtension(String::class)
interface StringGraph {
  // No binding provided.
  val someString: String

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createStringGraph(): StringGraph
  }
}

// Verify the graph itself and the factory can be excluded. Without the exclusions there would
// be missing bindings.
@DependencyGraph(
  scope = AppScope::class,
  excludes = [LoggedInGraph::class, StringGraph.Factory::class]
)
interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  return "OK"
}
