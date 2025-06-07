abstract class GrandParent<T : Any, R : Any> {
  @Inject lateinit var grandParentT: T
  @Inject lateinit var grandParentR: R
}

abstract class Parent<T : Any, R : Any> : GrandParent<R, T>() {
  @Inject lateinit var parentT: T
  @Inject lateinit var parentR: R
}

@Inject
class ExampleClass<T : Any> : Parent<T, String>() {
  @Inject lateinit var value: T
  @Inject lateinit var values: List<T>
  @Inject lateinit var mapValues: Map<T, List<T>>

  // Setter mid-properties to ensure ordering doesn't matter
  lateinit var functionSet: T

  @Inject
  fun functionMemberInject(value: T) {
    functionSet = value
  }

  lateinit var setterSet: T
    @Inject set
}

@DependencyGraph
interface AppGraph {
  val exampleClass: ExampleClass<Int>

  @Provides fun provideString(): String = "Hello, world!"

  @Provides fun provideInt(): Int = 3

  @Provides fun provideInts(): List<Int> = listOf(3)

  @Provides fun provideIntMap(int: Int, ints: List<Int>): Map<Int, List<Int>> = mapOf(3 to ints)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val exampleClass = graph.exampleClass
  assertEquals(exampleClass.value, 3)
  assertEquals(exampleClass.setterSet, 3)
  assertEquals(exampleClass.values, listOf(3))
  assertEquals(exampleClass.mapValues, mapOf(3 to listOf(3)))
  assertEquals(exampleClass.functionSet, 3)
  assertEquals(exampleClass.parentT, 3)
  assertEquals(exampleClass.parentR, "Hello, world!")
  assertEquals(exampleClass.grandParentT, "Hello, world!")
  assertEquals(exampleClass.grandParentR, 3)
  return "OK"
}
