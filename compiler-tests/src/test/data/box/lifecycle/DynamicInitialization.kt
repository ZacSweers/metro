// ENABLE_SUSPEND_PROVIDERS

@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

@DependencyGraph
interface ExampleGraph : SuspendCloseableGraph {
  @Provides fun value(): String = "default"

  @OnGraphInit
  suspend fun initialize(events: Events, value: String) {
    events.values += value
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(@Provides events: Events): ExampleGraph
  }
}

@BindingContainer
object Replacement {
  @Provides suspend fun value(): String = "replacement"
}

fun box(): String = runBlocking {
  val events = Events()
  val factory = createDynamicGraphFactory<ExampleGraph.Factory>(Replacement)
  val graph = factory.create(events)
  assertEquals(listOf("replacement"), events.values)
  graph.close()
  "OK"
}
