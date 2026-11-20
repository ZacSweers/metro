package test

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@GraphExtension(scope = Unit::class)
interface StringGraph : LoggedInGraph.Factory {
  val string: String

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun create(@Provides string: String): StringGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val stringGraph = exampleGraph.create("Hello")
  val loggedInGraph = stringGraph.createLoggedInGraph()
  assertEquals("Hello", loggedInGraph.string)
  return "OK"
}
