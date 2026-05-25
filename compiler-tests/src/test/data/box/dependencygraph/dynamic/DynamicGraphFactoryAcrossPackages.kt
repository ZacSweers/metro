// https://github.com/ZacSweers/metro/issues/2324
// Two call sites in different packages must each generate their own dynamic graph impl. If they
// shared one, the second would reference the first's private (on the JVM, package-private) nested
// FactoryImpl and fail at runtime with IllegalAccessError.
// FILE: AppGraph.kt
package app

@DependencyGraph
interface AppGraph {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: Int): AppGraph
  }
}

@BindingContainer
class TestIntProvider(private val value: Int) {
  @Provides fun provideInt(): Int = value
}

// FILE: FirstTest.kt
package app.first

import app.AppGraph
import app.TestIntProvider

class FirstTest {
  val graph: AppGraph = createDynamicGraphFactory<AppGraph.Factory>(TestIntProvider(4)).create(3)
}

// FILE: SecondTest.kt
package app.second

import app.AppGraph
import app.TestIntProvider

class SecondTest {
  val graph: AppGraph = createDynamicGraphFactory<AppGraph.Factory>(TestIntProvider(7)).create(3)
}

// FILE: box.kt
package app

import app.first.FirstTest
import app.second.SecondTest

fun box(): String {
  val first = FirstTest().graph
  val second = SecondTest().graph
  assertEquals(4, first.int)
  assertEquals(7, second.int)
  // Each call site gets its own impl, nested in its own class in its own package
  assertEquals("app.first.FirstTest", first::class.java.name.substringBefore('$'))
  assertEquals("app.second.SecondTest", second::class.java.name.substringBefore('$'))
  return "OK"
}
