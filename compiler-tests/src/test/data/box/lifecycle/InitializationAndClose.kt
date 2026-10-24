@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

@DependencyGraph
interface LifecycleGraph : CloseableGraph {
  val events: Events

  @OnGraphInit
  fun initialize(events: Events) {
    events.values += "init"
  }

  @OnGraphClose
  fun shutdown(events: Events) {
    events.values += "close"
  }

  @OnGraphInit
  @OnGraphClose
  fun both(events: Events) {
    events.values += "both"
  }

  @OnGraphInit
  fun defaults(value: String = "fallback") {
    check(value == "fallback")
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): LifecycleGraph
  }
}

fun box(): String {
  val events = Events()
  val graph = createGraphFactory<LifecycleGraph.Factory>().create(events)
  assertEquals(setOf("init", "both"), events.values.toSet())
  assertEquals(2, events.values.size)
  graph.close()
  graph.close()
  assertEquals(4, events.values.size)
  assertEquals(1, events.values.count { it == "close" })
  assertEquals(2, events.values.count { it == "both" })
  assertSame(events, graph.events)
  return "OK"
}
