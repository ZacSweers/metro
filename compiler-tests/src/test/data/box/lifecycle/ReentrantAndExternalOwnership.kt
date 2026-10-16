@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

class ExternalGraph : CloseableGraph {
  var closes = 0
  override fun close() { closes++ }
}

@DependencyGraph
interface ParentGraph : CloseableGraph {
  val child: ChildGraph
  val external: ExternalGraph
  val closed: Boolean
  val children: String

  @Provides fun closed(): Boolean = true
  @Provides fun children(): String = "value"

  @OnGraphClose
  fun finish(events: Events) {
    close()
    events.values += "parent"
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events, @Provides external: ExternalGraph): ParentGraph
  }
}

@GraphExtension
interface ChildGraph : CloseableGraph {
  @OnGraphClose
  fun finish(events: Events) {
    close()
    events.values += "child"
  }
}

fun box(): String {
  val events = Events()
  val external = ExternalGraph()
  val parent = createGraphFactory<ParentGraph.Factory>().create(events, external)
  parent.child
  assertTrue(parent.closed)
  assertEquals("value", parent.children)
  parent.close()
  parent.close()
  assertEquals(listOf("child", "parent"), events.values)
  assertEquals(0, external.closes)
  assertSame(external, parent.external)
  assertFailsWith<IllegalStateException> { parent.child }
  return "OK"
}
