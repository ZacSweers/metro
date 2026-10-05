// MIN_COMPILER_VERSION: 2.5.0-Beta1
// COMPANION_MODE: COMPANION_BLOCK

@DependencyGraph
interface EmptyGraph {
  companion { @Provides val providedText: String get() = "OK" }
  val text: String
  companion object { val marker = "user companion" }
}

@DependencyGraph
interface NamedGraph {
  val text: String
  @DependencyGraph.Factory
  fun interface Factory {
    fun build(@Provides text: String): NamedGraph
  }
}

@DependencyGraph
interface ClassFactoryGraph {
  val number: Int
  @DependencyGraph.Factory
  abstract class Factory {
    abstract fun create(@Provides number: Int): ClassFactoryGraph
  }
}

fun box(): String {
  val intrinsic = createGraph<EmptyGraph>()
  assertEquals("OK", intrinsic.text)
  assertEquals("user companion", EmptyGraph.Companion.marker)
  assertEquals("factory", createGraphFactory<NamedGraph.Factory>().build("factory").text)
  assertEquals(7, createGraphFactory<ClassFactoryGraph.Factory>().create(7).number)
  assertEquals("OK", EmptyGraph().text)
  assertEquals("named", NamedGraph.build("named").text)
  assertEquals(3, ClassFactoryGraph.factory().create(3).number)
  val creator: (String) -> NamedGraph = NamedGraph::build
  assertEquals("reference", creator("reference").text)
  return "OK"
}
