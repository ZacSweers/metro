// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// An unrelated companion factory() function doesn't replace the generated factory.
@DependencyGraph
interface AppGraph {
  val value: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: String): AppGraph
  }

  companion object {
    fun factory(name: String): String = "user-$name"
  }
}

fun box(): String {
  assertEquals("graph", createGraphFactory<AppGraph.Factory>().create("graph").value)
  assertEquals("block", AppGraph.create("block").value)
  assertEquals("user-x", AppGraph.factory("x"))
  return "OK"
}
