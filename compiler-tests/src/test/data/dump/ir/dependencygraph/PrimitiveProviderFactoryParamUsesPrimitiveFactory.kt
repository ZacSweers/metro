@DependencyGraph
interface AppGraph {
  val int: Int
  val intProvider: () -> Int
  val longProvider: () -> Long
  val longProvider2: () -> Long

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides int: Int, @Provides long: Long): AppGraph
  }
}
