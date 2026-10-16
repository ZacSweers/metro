// ENABLE_SUSPEND_PROVIDERS
// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 1

@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

@DependencyGraph(AppScope::class)
interface ParentGraph : SuspendCloseableGraph, ChildGraph.Factory {
  suspend fun value(): Int

  @Provides
  @SingleIn(AppScope::class)
  suspend fun provideValue(): Int = 42

  @OnGraphInit
  suspend fun initialize(events: Events, value: Int) {
    events.values += "parent:$value"
  }

  @OnGraphClose
  suspend fun finish(events: Events, value: Int) {
    events.values += "parent-close:$value"
  }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(@Provides events: Events): ParentGraph
  }
}

@GraphExtension
interface ChildGraph : SuspendCloseableGraph {
  @OnGraphInit
  suspend fun initialize(events: Events, value: Int) {
    events.values += "child:$value"
  }

  @OnGraphClose
  suspend fun finish(events: Events) {
    events.values += "child-close"
  }

  @GraphExtension.Factory
  fun interface Factory {
    suspend fun createChild(): ChildGraph
  }
}

fun box(): String = runBlocking {
  val events = Events()
  val factory = createGraphFactory<ParentGraph.Factory>()
  val parent = factory.create(events)
  assertEquals(listOf("parent:42"), events.values)
  parent.createChild()
  assertEquals(listOf("parent:42", "child:42"), events.values)
  parent.close()
  parent.close()
  assertEquals(listOf("parent:42", "child:42", "child-close", "parent-close:42"), events.values)
  assertEquals(42, parent.value())
  assertFailsWith<IllegalStateException> { parent.createChild() }
  "OK"
}
