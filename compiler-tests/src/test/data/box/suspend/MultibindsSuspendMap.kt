// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// An explicit @Multibinds-declared map with suspend @IntoMap contributions, consumed as the
// deferred map form. The @Multibinds primer must itself be declared in the deferred form: a
// synchronous `Map<String, Int>` primer would be treated as a synchronous request over suspend
// values and rejected. Each value defers its own suspension, so no coroutines runtime is required.

@DependencyGraph
interface ExampleGraph {
  @Multibinds fun handlers(): Map<String, suspend () -> Int>

  val deferred: Map<String, suspend () -> Int>

  @Provides @IntoMap @StringKey("a") suspend fun provideA(): Int = 1

  @Provides @IntoMap @StringKey("b") suspend fun provideB(): Int = 2
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return runBlocking {
    val map = graph.deferred
    assertEquals(setOf("a", "b"), map.keys)
    assertEquals(1, map.getValue("a").invoke())
    assertEquals(2, map.getValue("b").invoke())
    "OK"
  }
}
