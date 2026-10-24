@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
  var fail = true
}

@DependencyGraph
interface InitializingGraph : CloseableGraph {
  @OnGraphInit
  fun initialize(events: Events) {
    events.values += "init"
    error("startup failed")
  }

  @OnGraphClose
  fun finish(events: Events) {
    events.values += "rollback"
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): InitializingGraph
  }
}

@DependencyGraph
interface ClosingGraph : CloseableGraph {
  val value: String

  @Provides
  fun provideValue(events: Events): String {
    events.values += "resolve"
    if (events.fail) {
      error("resolution failed")
    }
    return "available after close"
  }

  @OnGraphClose
  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER")
  fun finish(value: String, events: Events) {
    events.values += "close"
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): ClosingGraph
  }
}

fun box(): String {
  val events = Events()
  assertFailsWith<IllegalStateException> {
    createGraphFactory<InitializingGraph.Factory>().create(events)
  }
  assertEquals(listOf("init"), events.values)
  events.values.clear()
  val graph = createGraphFactory<ClosingGraph.Factory>().create(events)
  assertFailsWith<IllegalStateException> { graph.close() }
  events.fail = false
  graph.close()
  assertEquals(listOf("resolve"), events.values)
  assertEquals("available after close", graph.value)
  return "OK"
}
