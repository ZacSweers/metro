// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_DEPENDENCY

// MODULE: lib
@Inject
class Example(@OptionalDependency val value: String? = null)

// MODULE: main(lib)
@Inject
class Example2(@OptionalDependency val value: String? = null)

@DependencyGraph
interface AppGraph {
  val example: Example
  val example2: Example2
  val int: Int

  @Provides
  fun provideInt(@OptionalDependency long: Long? = null): Int = long?.toInt() ?: 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNull(null, graph.example.value)
  assertNull(null, graph.example2.value)
  assertEquals(3, graph.int)
  return "OK"
}
