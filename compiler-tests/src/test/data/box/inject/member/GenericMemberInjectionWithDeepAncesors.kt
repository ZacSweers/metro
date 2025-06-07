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
  assertEquals(3, exampleClass.value)
  assertEquals(3, exampleClass.setterSet)
  assertEquals(listOf(3), exampleClass.values)
  assertEquals(mapOf(3 to listOf(3)), exampleClass.mapValues)
  assertEquals(3, exampleClass.functionSet)
  assertEquals(3, exampleClass.parentT)
  assertEquals("Hello, world!", exampleClass.parentR)
  assertEquals("Hello, world!", exampleClass.grandParentT)
  assertEquals(3, exampleClass.grandParentR)
  return "OK"
}
