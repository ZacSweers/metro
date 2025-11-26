@DependencyGraph
interface AppGraph {
  // Some other class called Impl
  class Impl
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("AppGraph.Impl2", graph::class.qualifiedName)
  return "OK"
}