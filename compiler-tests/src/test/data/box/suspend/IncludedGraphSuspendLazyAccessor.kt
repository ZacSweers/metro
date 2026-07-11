// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// ^ runBlocking, JVM-only

var includedLazyComputations = 0

abstract class IncludedScope private constructor()

@DependencyGraph(scope = IncludedScope::class)
interface IncludedGraph {
  val value: SuspendLazy<String>

  val count: suspend () -> Int

  @Provides
  @SingleIn(IncludedScope::class)
  suspend fun provideValue(): String {
    includedLazyComputations++
    return "value"
  }

  @Provides suspend fun provideCount(): Int = 1
}

@Inject class IncludedConsumer(val value: String, val count: Int)

@DependencyGraph
interface IncludingGraph {
  suspend fun consumer(): IncludedConsumer

  val value: suspend () -> String

  val count: suspend () -> Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes includedGraph: IncludedGraph): IncludingGraph
  }
}

fun box(): String {
  val included = createGraph<IncludedGraph>()
  val graph = createGraphFactory<IncludingGraph.Factory>().create(included)
  return kotlinx.coroutines.runBlocking {
    assertEquals("value", graph.consumer().value)
    assertEquals(1, graph.consumer().count)
    assertEquals("value", graph.value())
    assertEquals(1, graph.count())
    assertEquals(1, includedLazyComputations)
    "OK"
  }
}
