// MODULE: lib
data class ExternalDependencies(
  val int: Int,
)

// MODULE: main(lib)
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
