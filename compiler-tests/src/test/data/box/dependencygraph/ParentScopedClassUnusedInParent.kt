@DependencyGraph(AppScope::class, isExtendable = true)
interface ParentGraph

@ContributesGraphExtension(Unit::class)
interface ChildGraph {

  val foo: Foo

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@SingleIn(AppScope::class)
@Inject
class Foo

fun box(): String {
  val parentGraph = createGraph<ParentGraph>()
  val childGraph = parentGraph.createChildGraph()
  assertNotNull(childGraph.foo)
  return "OK"
}