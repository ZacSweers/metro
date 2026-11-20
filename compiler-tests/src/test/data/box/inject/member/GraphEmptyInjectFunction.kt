package test

@DependencyGraph
interface ExampleGraph {
  fun inject(value: ExampleClass)
}

class ExampleClass

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val instance = ExampleClass()
  // noop call
  graph.inject(instance)
  return "OK"
}
