@DependencyGraph
interface ExampleGraph

@BindingContainer
object Bindings

fun example(
  graph: ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings),
  graph2: ExampleGraph = createDynamicGraph<ExampleGraph>(Bindings),
): Pair<ExampleGraph, ExampleGraph> {
  return graph to graph2
}

fun box(): String {
  val (graph1, graph2) = example()
  assertEquals(graph1::class.qualifiedName, graph2::class.qualifiedName)
  return "OK"
}