abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val string: String

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(@Includes stringProvider: StringProvider): LoggedInGraph
  }
}

class StringProvider(val value: String = "Hello")

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val child = graph.createLoggedInGraph(StringProvider("Hello"))
  assertEquals("Hello", child.string)
  return "OK"
}
