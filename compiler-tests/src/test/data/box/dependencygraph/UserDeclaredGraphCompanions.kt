// User-declared graph companions still get the generated creator, and their own `factory`
// functions keep working.
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

@DependencyGraph
interface NoFactoryGraph {
  val value: Long

  @Provides fun provideValue(): Long = 3L

  companion object {
    fun factory(name: String): String = "user-$name"
  }
}

fun box(): String {
  assertEquals("graph", createGraphFactory<AppGraph.Factory>().create("graph").value)
  assertEquals("graph", AppGraph.create("graph").value)
  assertEquals("user-x", AppGraph.factory("x"))

  assertEquals(2, createGraphFactory<AbstractFactoryGraph.Factory>().create(2).value)
  assertEquals(2, AbstractFactoryGraph.factory().create(2).value)
  assertEquals("user-x", AbstractFactoryGraph.factory("x"))

  assertEquals(3L, createGraph<NoFactoryGraph>().value)
  assertEquals(3L, NoFactoryGraph().value)
  assertEquals("user-x", NoFactoryGraph.factory("x"))
  return "OK"
}
