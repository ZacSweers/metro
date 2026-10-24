// MODULE: lib
@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

interface CommonLifecycle {
  @OnGraphInit
  @OnGraphClose
  fun common(events: Events) {
    events.values += "common"
  }
}

interface Left : CommonLifecycle
interface Right : CommonLifecycle

@ContributesTo(AppScope::class)
interface LifecycleContribution : Left, Right {
  @OnGraphInit
  @OnGraphClose
  override fun common(events: Events) {
    events.values += "override"
  }

  @OnGraphClose
  fun finish(events: Events) {
    events.values += "finish"
  }
}

// MODULE: main(lib)
@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph(AppScope::class)
interface ExampleGraph : CloseableGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): ExampleGraph
  }
}

fun box(): String {
  val events = Events()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(events)
  assertEquals(listOf("override"), events.values)
  graph.close()
  assertEquals(setOf("override", "finish"), events.values.toSet())
  assertEquals(3, events.values.size)
  assertEquals(2, events.values.count { it == "override" })
  return "OK"
}
