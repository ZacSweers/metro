// https://github.com/ZacSweers/metro/issues/1093

class ValueHolder {
  val aString = "Hello"
}

@DependencyGraph(AppScope::class)
interface AGraph {
  val bGraphFactory: BGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes valueHolder: ValueHolder): AGraph
  }
}

@GraphExtension
interface BGraph {
  val bString: String

  @Provides
  private fun string(valueHolder: ValueHolder): String = valueHolder.aString + "Nested"

  @GraphExtension.Factory
  interface Factory : () -> BGraph
}

fun box(): String {
  val aGraph = createGraphFactory<AGraph.Factory>().create(ValueHolder())
  val bGraph = aGraph.bGraphFactory()
  assertEquals("HelloNested", bGraph.bString)
  return "OK"
}