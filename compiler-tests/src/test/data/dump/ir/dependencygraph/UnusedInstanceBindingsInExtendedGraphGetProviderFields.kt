@SingleIn(AppScope::class)
@DependencyGraph(isExtendable = true)
interface AppGraph {
  @Provides private fun provideInt(): Int = 3

  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      // Unused in AppGraph but because it's extendable, it will get a field exposed anyway
      @Provides string: String
    ): AppGraph
  }
}
