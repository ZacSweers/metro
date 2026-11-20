// MODULE: lib
// FILE: Library.kt
package test

interface Base {
  @Provides fun provideString(value: Int = 2): String = value.toString()
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph
interface ExampleGraph : Base {
  val string: String
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("2", graph.string)
  return "OK"
}
