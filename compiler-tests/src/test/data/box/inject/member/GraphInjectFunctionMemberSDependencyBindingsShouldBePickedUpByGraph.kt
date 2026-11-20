package test

@DependencyGraph
interface ExampleGraph {
  fun inject(exampleClass: ExampleClass)
}
class ExampleClass {
  var int: Int = 0
  @Inject fun injectValues(dependency: Dependency) {
    this.int = dependency.int
  }
}
@Inject
class Dependency {
  val int: Int = 3
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val instance = ExampleClass()
  graph.inject(instance)
  assertEquals(3, instance.int)
  return "OK"
}
