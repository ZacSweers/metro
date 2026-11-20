// A missing optional binding must leave later topological sort checks satisfied.

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  fun foo(): Foo
}

@SingleIn(AppScope::class)
class Foo @Inject constructor(
  val bar: Bar,
  val text: String = "default"
)

@Inject class Bar

fun box(): String {
  assertEquals("default", createGraph<ExampleGraph>().foo().text)
  return "OK"
}
