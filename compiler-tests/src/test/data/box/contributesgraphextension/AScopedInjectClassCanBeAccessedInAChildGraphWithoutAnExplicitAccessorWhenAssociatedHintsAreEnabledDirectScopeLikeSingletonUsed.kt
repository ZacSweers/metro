package test

sealed interface LoggedInScope
@Scope annotation class Singleton

@Inject
@Singleton
class Dependency

@Singleton
@DependencyGraph(AppScope::class)
interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val childDependency: Dependency

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
