// MODULE: lib
@DependencyGraph
interface AppGraph {
  val value: String

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides value: String): AppGraph
  }

  companion object {
    fun factory(name: String): String = "user-$name"
  }
}

@DependencyGraph
interface AbstractFactoryGraph {
  val value: Int

  @DependencyGraph.Factory
  abstract class Factory {
    abstract fun create(@Provides value: Int): AbstractFactoryGraph
  }

  companion object {
    fun factory(name: String): String = "user-$name"
  }
}

// MODULE: main(lib)
fun box(): String {
  assertEquals("graph", createGraphFactory<AppGraph.Factory>().create("graph").value)
  assertEquals("graph", AppGraph.create("graph").value)
  assertEquals("user-x", AppGraph.factory("x"))

  assertEquals(2, createGraphFactory<AbstractFactoryGraph.Factory>().create(2).value)
  assertEquals(2, AbstractFactoryGraph.factory().create(2).value)
  assertEquals("user-x", AbstractFactoryGraph.factory("x"))
  return "OK"
}
