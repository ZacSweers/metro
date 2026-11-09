// METRO_JVM_ONLY

@DependencyGraph
interface Root {
  val parentFactory: Parent.Factory
}

@GraphExtension
interface Parent {
  val first: First.Factory
  val second: Second.Factory

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): Parent
  }
}

object First {
  @GraphExtension interface Factory
}

object Second {
  @GraphExtension interface Factory
}

fun box(): String {
  val factory = createGraph<Root>().parentFactory
  val parent = factory.create()
  val first = parent.first.javaClass
  val second = parent.second.javaClass

  // The parent's factory already occupies FactoryImpl when Metro names the two children.
  assertEquals("FactoryImpl", factory.javaClass.simpleName)
  assertEquals(parent.javaClass, factory.javaClass.enclosingClass)
  assertEquals(parent.javaClass, first.enclosingClass)
  assertEquals(parent.javaClass, second.enclosingClass)
  assertEquals(setOf("FactoryImpl2", "FactoryImpl3"), setOf(first.simpleName, second.simpleName))
  return "OK"
}
