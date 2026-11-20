package test

abstract class LoggedInScope

interface BaseExtension<T> {
  val value: T
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph : BaseExtension<Int> {

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val loggedInGraph = exampleGraph.createLoggedInGraph()
  assertEquals(0, loggedInGraph.value)
  return "OK"
}
