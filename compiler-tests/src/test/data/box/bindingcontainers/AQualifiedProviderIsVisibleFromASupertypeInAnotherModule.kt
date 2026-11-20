// MODULE: lib
// FILE: Library.kt
package test

interface Base {
  @Provides @Named("int") fun provideInt(): Int = 2
}

// MODULE: main(lib)
// FILE: Main.kt
package test

@DependencyGraph
interface ExampleGraph : Base {
  @Named("int")
  val int: Int
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(2, graph.int)
  return "OK"
}
