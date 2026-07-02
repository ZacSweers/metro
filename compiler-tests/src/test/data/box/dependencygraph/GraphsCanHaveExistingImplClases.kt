@DependencyGraph
interface AppGraph {
  // Some other class called Impl
  class Impl
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("Impl2", graph::class.simpleName)
  return "OK"
}
