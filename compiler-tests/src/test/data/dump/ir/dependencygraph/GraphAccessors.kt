// Golden image test to ensure we don't generate unnecessary wrapping providers over accessors
interface Accessors {
  val string: String
  val intProvider: Provider<Int>
}

@DependencyGraph
interface ExampleGraph {
  val string: String
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes accessors: Accessors): ExampleGraph
  }
}