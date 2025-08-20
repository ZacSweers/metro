sealed interface LoggedInScope

interface Bob

@Inject @SingleIn(AppScope::class) @ContributesBinding(AppScope::class) class Dependency : Bob

@DependencyGraph(scope = AppScope::class) interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val childDependency: Bob

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val parentGraph = createGraph<ExampleGraph>()
  val childGraph = parentGraph.createLoggedInGraph()
  assertNotNull(childGraph.childDependency)
  return "OK"
}