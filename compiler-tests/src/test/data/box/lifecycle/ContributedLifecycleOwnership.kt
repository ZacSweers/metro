// MODULE: lib
@file:OptIn(ExperimentalMetroApi::class)

class Events { val values = mutableListOf<String>() }

@ContributesTo(AppScope::class)
interface LifecycleContribution : CloseableGraph {
  @OnGraphInit fun initialize(events: Events) { events.values += "init" }
  @OnGraphClose fun finish(events: Events) { events.values += "close" }
}

// MODULE: main(lib)
@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): ExampleGraph
  }
}

fun box(): String {
  val events = Events()
  val graph = createGraphFactory<ExampleGraph.Factory>().create(events)
  assertEquals(listOf("init"), events.values)
  graph.asContribution<LifecycleContribution>().close()
  assertEquals(listOf("init", "close"), events.values)
  return "OK"
}
