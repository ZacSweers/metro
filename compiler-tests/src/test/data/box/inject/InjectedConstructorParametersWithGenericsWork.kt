class ExampleClass<T> @Inject constructor(
  val value: T,
  val values: List<T>
)

@DependencyGraph
interface AppGraph {
  val exampleClass: ExampleClass<Int>

  @Provides fun provideInt(): Int = 3
  @Provides fun provideInts(): List<Int> = listOf(3)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val exampleClass = graph.exampleClass
  assertEquals(exampleClass.value, 3)
  assertEquals(exampleClass.values, listOf(3))
  return "OK"
}
