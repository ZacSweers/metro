package test

sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency
@Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val childDependency: ChildDependency

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
