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
  val cGraph: CGraph.Factory

  @GraphExtension.Factory
  interface Factory {
    fun create(): BGraph
  }
}

@GraphExtension
interface CGraph {
  val aString: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): CGraph
  }
}

fun box(): String {
  val aGraph = createGraphFactory<AGraph.Factory>().create(ValueHolder())
  val bGraph = aGraph.bGraphFactory.create()
  assertEquals("Hello", bGraph.cGraph.create().aString)
  return "OK"
}
