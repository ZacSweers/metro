@AssistedInject
class Example<T>(@Assisted val inputT: T, val graphT: T) {
  @AssistedFactory
  fun interface Factory<T> {
    fun create(inputT: T): Example<T>
  }

  @AssistedFactory
  fun interface Factory2 {
    fun create(inputT: Int): Example<Int>
  }
}

@AssistedInject
class ExampleWithDifferent<T, R>(@Assisted val inputT: T, val graphT: R) {
  @AssistedFactory
  fun interface Factory<T, R> {
    fun create(inputT: T): ExampleWithDifferent<T, R>
  }

  @AssistedFactory
  fun interface Factory2<T> {
    fun create(inputT: Int): ExampleWithDifferent<Int, T>
  }
}

@DependencyGraph
interface AppGraph {
  val exampleFactory: Example.Factory<Int>
  val exampleFactory2: Example.Factory2

  val exampleFactory3: ExampleWithDifferent.Factory<Int, Int>
  val exampleFactory4: ExampleWithDifferent.Factory2<Int>

  @Provides
  val int: Int
    get() = 2
}

fun box(): String {
  val graph = createGraph<AppGraph>()

  val factory = graph.exampleFactory
  val example = factory.create(3)
  assertEquals(3, example.inputT)
  assertEquals(2, example.graphT)
  val factory2 = graph.exampleFactory2
  val example2 = factory.create(3)
  assertEquals(3, example2.inputT)
  assertEquals(2, example2.graphT)

  val factory3 = graph.exampleFactory3
  val example3 = factory3.create(3)
  assertEquals(3, example3.inputT)
  assertEquals(2, example3.graphT)
  val factory4 = graph.exampleFactory4
  val example4 = factory.create(3)
  assertEquals(3, example4.inputT)
  assertEquals(2, example4.graphT)

  return "OK"
}
