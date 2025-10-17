// MODULE: lib
interface Base {
  @OptionalDependency
  val absentBoolean: Boolean
    get() = true

  @OptionalDependency
  fun presentChar(): Char = 'b'
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph : Base {
  @OptionalDependency
  val absentInt: Int
    get() = 3

  @OptionalDependency
  fun presentLong(): Long = 3L

  @Provides
  fun provideLong(): Long = 4L

  @Provides
  fun provideChar(): Char = 'a'
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.absentBoolean)
  assertEquals('a', graph.presentChar())
  assertEquals(3, graph.absentInt)
  assertEquals(4L, graph.presentLong())
  return "OK"
}
