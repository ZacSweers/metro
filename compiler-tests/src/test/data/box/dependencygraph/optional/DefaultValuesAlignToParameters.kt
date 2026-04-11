@Inject
data class Example(
  val foo: String = "foo",
  val one: Int = 1,
  val bar: String = "bar",
  val two: Int = 2,
)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val exampleFactory: Provider<Example>
}

fun box(): String {
  val graph = createGraph<AppGraph>()

  assertEquals(Example(), graph.exampleFactory())
  return "OK"
}
