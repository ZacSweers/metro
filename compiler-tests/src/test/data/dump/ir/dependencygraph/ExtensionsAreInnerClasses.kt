@GraphExtension
interface LoggedInGraph {
  val id: String
  val count: Int

  @GraphExtension.Factory
  interface Factory {
    fun createLoggedInGraph(@Provides id: String): LoggedInGraph
  }
}

@DependencyGraph
interface AppGraph : LoggedInGraph.Factory {
  @Provides fun provideCount(): Int = 3
}