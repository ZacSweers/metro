// Repro for a bug where using @Includes on a class is fine, but not a data class.
data class ExternalDependencies(
  val int: Int,
)

@DependencyGraph
interface AppGraph {
  val int: Int

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes dependencies: ExternalDependencies
    ): AppGraph
  }
}

fun box(): String {
  val appGraph = createGraphFactory<AppGraph.Factory>().create(ExternalDependencies(3))
  assertEquals(3, appGraph.int)
  return "OK"
}
