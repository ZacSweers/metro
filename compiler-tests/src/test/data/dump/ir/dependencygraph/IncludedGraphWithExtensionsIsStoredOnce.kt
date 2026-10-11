@GraphExtension
interface ChildGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@DependencyGraph
interface IncludedGraph : ChildGraph.Factory {
  val string: String

  @Provides fun provideString(): String = "included"
}

@DependencyGraph
interface AppGraph {
  val string: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes included: IncludedGraph): AppGraph
  }
}
