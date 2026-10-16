// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// A companion-block overload with the SAM's name makes FIR request the creator early.
@DependencyGraph
interface AppGraph {
  val value: Int

  @DependencyGraph.Factory
  fun interface Factory {
    fun build(@Provides value: Int): AppGraph
  }

  companion {
    fun build(value: Int?): AppGraph = build(value ?: 0)
  }
}

fun box(): String {
  assertEquals(1, AppGraph.build(1).value)
  assertEquals(0, AppGraph.build(null).value)
  return "OK"
}
