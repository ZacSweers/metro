// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// The factory's supertype is nested in the graph and written by its short name.
@DependencyGraph
interface AppGraph {
  val value: Int

  interface Base {
    fun create(@Provides x: Int): AppGraph
  }

  @DependencyGraph.Factory
  interface Factory : Base
}

fun box(): String {
  assertEquals(1, AppGraph.create(1).value)
  return "OK"
}
