// ENABLE_SUSPEND_PROVIDERS

var includedLazyComputations = 0

abstract class IncludedScope private constructor()

class Port(val value: Int)

@DependencyGraph(scope = IncludedScope::class)
interface IncludedGraph {
  val value: Provider<SuspendLazy<String>>

  val port: Provider<SuspendLazy<Port>>

  val count: suspend () -> Int

  @Provides
  @SingleIn(IncludedScope::class)
  suspend fun provideValue(): String {
    includedLazyComputations++
    return "value"
  }

  @Provides suspend fun provideCount(): Int = 1

  @Provides fun providePort(): Port = Port(8080)
}

@Inject class IncludedConsumer(val value: String, val count: Int)

@DependencyGraph
interface IncludingGraph {
  suspend fun consumer(): IncludedConsumer

  val value: suspend () -> String

  val nestedValue: Provider<SuspendLazy<String>>

  val port: Provider<SuspendLazy<Port>>

  suspend fun portValue(): Port

  val count: suspend () -> Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes includedGraph: IncludedGraph): IncludingGraph
  }
}

fun box(): String {
  val included = createGraph<IncludedGraph>()
  val graph = createGraphFactory<IncludingGraph.Factory>().create(included)
  return runBlocking {
    assertEquals("value", graph.consumer().value)
    assertEquals(1, graph.consumer().count)
    assertEquals("value", graph.value())
    assertEquals("value", graph.nestedValue().value())
    assertEquals(8080, graph.port().value().value)
    assertEquals(8080, graph.portValue().value)
    assertEquals(1, graph.count())
    assertEquals(1, includedLazyComputations)
    "OK"
  }
}
