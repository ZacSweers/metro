// MODULE: lib
// FILE: Library.kt
package test

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val loggedInGraph = exampleGraph.createLoggedInGraph()
  assertEquals(0, loggedInGraph.int)
  return "OK"
}
