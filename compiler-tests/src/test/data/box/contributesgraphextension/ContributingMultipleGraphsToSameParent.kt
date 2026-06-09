// FILE: file1.kt
package test

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val dependency: Dependency

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

// FILE: file2.kt
package test2

import test.LoggedInScope
import test.Dependency

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val dependency: Dependency

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph2(): LoggedInGraph
  }
}

// FILE: ExampleGraph.kt
package test

sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val dependency: Dependency
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val loggedInGraph1 = graph.createLoggedInGraph()
  val loggedInGraph2 = graph.createLoggedInGraph2()
  assertNotEquals(loggedInGraph1::class, loggedInGraph2::class)
  assertEquals(graph.dependency, loggedInGraph1.dependency)
  assertEquals(graph.dependency, loggedInGraph2.dependency)
  return "OK"
}
