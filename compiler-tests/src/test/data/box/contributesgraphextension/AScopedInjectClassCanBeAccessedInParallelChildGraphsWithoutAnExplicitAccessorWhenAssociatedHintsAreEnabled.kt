package test

interface LoggedInScope
interface OtherScope

@Inject
@SingleIn(AppScope::class)
class Dependency

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val childDependency: Dependency

    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
    }
}

@GraphExtension(OtherScope::class)
interface OtherGraph {
  val childDependency: Dependency

    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createOtherGraph(): OtherGraph
    }
}

fun box(): String {
  val parentGraph = createGraph<ExampleGraph>()
  val childGraph1 = parentGraph.createLoggedInGraph()
  val childGraph2 = parentGraph.createOtherGraph()
  val childGraph1Dep = childGraph1.childDependency
  assertNotNull(childGraph1Dep)
  assertSame(childGraph2.childDependency, childGraph1Dep)
  val childGraph2Dep = childGraph2.childDependency
  assertNotNull(childGraph2Dep)
  assertSame(childGraph1.childDependency, childGraph2Dep)
  return "OK"
}
