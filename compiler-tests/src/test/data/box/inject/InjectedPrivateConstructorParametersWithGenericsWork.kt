// https://github.com/ZacSweers/metro/issues/833
@Inject
class ExampleClass<T> private constructor(
  val value: T,
  val values: List<T>,
  val mapValues: Map<T, List<T>>
)

@DependencyGraph
interface AppGraph {
  val exampleClass: ExampleClass<Int>

  @Provides fun provideInt(): Int = 3
  @Provides fun provideInts(): List<Int> = listOf(3)
  @Provides fun provideIntMap(int: Int, ints: List<Int>): Map<Int, List<Int>> = mapOf(3 to ints)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val exampleClass = graph.exampleClass
  assertEquals(exampleClass.value, 3)
  assertEquals(exampleClass.values, listOf(3))
  assertEquals(exampleClass.mapValues, mapOf(3 to listOf(3)))
  return "OK"
}
