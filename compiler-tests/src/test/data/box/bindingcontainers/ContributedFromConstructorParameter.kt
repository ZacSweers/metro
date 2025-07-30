@BindingContainer
class InjectedFromConstructorParameter(
  @get:Provides val string: String,
)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes container: InjectedFromConstructorParameter): AppGraph
  }
}

fun box(): String {
  val container = InjectedFromConstructorParameter(string = "abc")
  val graph = createGraphFactory<AppGraph.Factory>().create(container)
  assertEquals(graph.string, "abc")
  return "OK"
}
