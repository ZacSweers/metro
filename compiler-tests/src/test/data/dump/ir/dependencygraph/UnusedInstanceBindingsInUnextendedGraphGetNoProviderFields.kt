@SingleIn(AppScope::class)
@DependencyGraph
interface AppGraph {
  @Provides private fun provideInt(): Int = 3

  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      // Unused in AppGraph
      @Provides string: String
    ): AppGraph
  }
}
