// MODULE: lib
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

  @AssistedFactory
  fun interface NestedFactory<T> {
    fun create(inputT: List<T>): Example<List<T>>
  }

  interface BaseFactory<T> {
    fun create(inputT: T): Example<T>
  }

  @AssistedFactory
  fun interface InheritedFactory<T> : BaseFactory<List<T>>
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

  @AssistedFactory
  fun interface ReorderedFactory<T, R> {
    fun create(inputT: R): ExampleWithDifferent<R, T>
  }
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph {
  val exampleFactory: Example.Factory<Int>
  val exampleFactory2: Example.Factory2

  val exampleFactory3: ExampleWithDifferent.Factory<Int, Int>
  val exampleFactory4: ExampleWithDifferent.Factory2<Int>
  val nestedFactory: Example.NestedFactory<Int>
  val inheritedFactory: Example.InheritedFactory<Int>
  val reorderedFactory: ExampleWithDifferent.ReorderedFactory<String, Int>

  @Provides
  val int: Int
    get() = 2

  @Provides
  val ints: List<Int>
    get() = listOf(2)

  @Provides
  val string: String
    get() = "graph"
}

fun box(): String {
  val graph = createGraph<AppGraph>()

  val factory = graph.exampleFactory
  val example = factory.create(3)
  assertEquals(3, example.inputT)
  assertEquals(2, example.graphT)
  val factory2 = graph.exampleFactory2
  val example2 = factory2.create(3)
  assertEquals(3, example2.inputT)
  assertEquals(2, example2.graphT)

  val factory3 = graph.exampleFactory3
  val example3 = factory3.create(3)
  assertEquals(3, example3.inputT)
  assertEquals(2, example3.graphT)
  val factory4 = graph.exampleFactory4
  val example4 = factory4.create(3)
  assertEquals(3, example4.inputT)
  assertEquals(2, example4.graphT)

  // Nested and inherited signatures keep the factory's T inside List<T>.
  val nested = graph.nestedFactory.create(listOf(3))
  assertEquals(listOf(3), nested.inputT)
  assertEquals(listOf(2), nested.graphT)
  val inherited = graph.inheritedFactory.create(listOf(4))
  assertEquals(listOf(4), inherited.inputT)
  assertEquals(listOf(2), inherited.graphT)

  // Distinct argument types check that the return type controls constructor substitution.
  val reordered = graph.reorderedFactory.create(5)
  assertEquals(5, reordered.inputT)
  assertEquals("graph", reordered.graphT)

  return "OK"
}
