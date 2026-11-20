package test

@DependencyGraph
interface ExampleGraph {
  @Multibinds(allowEmpty = true) val strings: Set<String>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val strings = graph.strings
  assertTrue(strings.isEmpty())
  return "OK"
}
