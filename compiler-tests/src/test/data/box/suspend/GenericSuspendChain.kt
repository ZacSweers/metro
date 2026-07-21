// ENABLE_SUSPEND_PROVIDERS

// A generic @Inject class participating in a suspend chain with two concrete instantiations.
// Verifies type substitution through the generated suspend factories.

@Inject class Box<T>(val value: T)

@Inject class Consumer(val box: Box<String>)

@DependencyGraph
interface ExampleGraph {
  suspend fun consumer(): Consumer

  suspend fun intBox(): Box<Int>

  @Provides suspend fun provideString(): String = "boxed"

  @Provides suspend fun provideInt(): Int = 42
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return runBlocking {
    assertEquals("boxed", graph.consumer().box.value)
    assertEquals(42, graph.intBox().value)
    "OK"
  }
}
