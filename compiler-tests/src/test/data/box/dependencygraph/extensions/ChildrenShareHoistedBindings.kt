@Inject class Dependency

@Inject class Shared(val dependency: Dependency)

@GraphExtension
interface ChildA {
  val shared: Shared
  val strings: Set<String>

  @GraphExtension.Factory
  interface Factory {
    fun createChildA(): ChildA
  }
}

@GraphExtension
interface ChildB {
  val shared: Shared
  val strings: Set<String>

  @GraphExtension.Factory
  interface Factory {
    fun createChildB(): ChildB
  }
}

@DependencyGraph
interface AppGraph : ChildA.Factory, ChildB.Factory {
  @Provides fun provideInt(): Int = 3

  @Provides @IntoSet fun provideOne(int: Int): String = "one$int"

  @Provides @IntoSet fun provideTwo(int: Int): String = "two$int"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val childA = graph.createChildA()
  val childB = graph.createChildB()
  assertEquals(setOf("one3", "two3"), childA.strings)
  assertEquals(setOf("one3", "two3"), childB.strings)
  // Unscoped bindings still build a new instance every time
  assertNotSame(childA.shared, childA.shared)
  assertNotSame(childA.shared, childB.shared)
  assertNotSame(childA.shared.dependency, childB.shared.dependency)
  return "OK"
}
