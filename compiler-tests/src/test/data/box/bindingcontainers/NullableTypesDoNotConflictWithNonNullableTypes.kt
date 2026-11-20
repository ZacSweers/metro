package test

interface Base {
  @Provides fun provideInt(): Int = 2
  @Provides fun provideNullNullableInt(): Int? = null
  @Provides fun provideString(): String = "Hello"
  @Provides fun provideNotNullNullableString(): String? = "NullableHello"
}
@DependencyGraph
interface ExampleGraph : Base {
  val int: Int
  val nullableInt: Int?
  val string: String
  val nullableString: String?
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals(2, graph.int)
  assertEquals(null, graph.nullableInt)
  assertEquals("Hello", graph.string)
  assertEquals("NullableHello", graph.nullableString)
  return "OK"
}
