// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

// Whole-collection access to ordinary (non-suspend) multibindings through suspend wrappers:
// suspend () -> Set<T> and suspend () -> Map<K, V> adapt the Provider form.

@DependencyGraph
interface ExampleGraph {
  val ints: suspend () -> Set<Int>

  val labels: suspend () -> Map<String, Int>

  @Provides @IntoSet fun provideOne(): Int = 1

  @Provides @IntoSet fun provideTwo(): Int = 2

  @Provides @IntoMap @StringKey("a") fun provideA(): Int = 1

  @Provides @IntoMap @StringKey("b") fun provideB(): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return kotlinx.coroutines.runBlocking {
    assertEquals(setOf(1, 2), graph.ints())
    assertEquals(mapOf("a" to 1, "b" to 2), graph.labels())
    "OK"
  }
}
