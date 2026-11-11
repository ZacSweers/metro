// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

interface BaseFactory<T, G> {
  fun create(@Provides values: List<T> = emptyList()): G
}

@DependencyGraph
interface AppGraph {
  val values: List<String>

  @DependencyGraph.Factory fun interface Factory : BaseFactory<String, AppGraph>
}

fun box(): String {
  assertEquals(emptyList(), AppGraph.create().values)
  assertEquals(listOf("a"), AppGraph.create(listOf("a")).values)
  return "OK"
}
