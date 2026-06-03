@DependencyGraph
interface AppGraph {
  val int: Int
  val intProvider: () -> Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides int: Int): AppGraph
  }
}
