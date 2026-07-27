// ENABLE_SUSPEND_PROVIDERS
// WITHOUT_RUNTIME_COROUTINES

// An empty @Multibinds map consumed as the deferred suspend map form yields an empty map.

@DependencyGraph
interface ExampleGraph {
  @Multibinds(allowEmpty = true) fun handlers(): Map<String, Int>

  val deferred: Map<String, suspend () -> Int>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  return runBlocking {
    assertTrue(graph.deferred.isEmpty())
    "OK"
  }
}
