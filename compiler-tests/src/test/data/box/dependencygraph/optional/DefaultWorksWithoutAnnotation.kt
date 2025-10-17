// OPTIONAL_DEPENDENCY_BEHAVIOR: DEFAULT

// MODULE: lib
@Inject
class Example(val value: String? = null)

// MODULE: main(lib)
@Inject
class Example2(val value: String? = null)

@DependencyGraph
interface AppGraph {
  val example: Example
  val example2: Example2
  val int: Int

  @Provides
  fun provideInt(long: Long? = null): Int = long?.toInt() ?: 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNull(null, graph.example.value)
  assertNull(null, graph.example2.value)
  assertEquals(3, graph.int)
  return "OK"
}
