@file:OptIn(ExperimentalMetroApi::class)

class Events {
  val values = mutableListOf<String>()
}

@DependencyGraph
interface ParentGraph : CloseableGraph {
  val childFactory: ChildGraph.Factory

  @OnGraphClose
  fun finish(events: Events) {
    events.values += "parent"
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides events: Events): ParentGraph
  }
}

@GraphExtension
interface ChildGraph : CloseableGraph {
  val grandchildFactory: GrandchildGraph.Factory

  @OnGraphInit
  fun initialize(events: Events, name: String) {
    if (name == "bad-init") {
      error("initialization failed")
    }
    events.values += "init:$name"
  }

  @OnGraphClose
  fun finish(events: Events, name: String) {
    events.values += name
    if (name == "bad-close") {
      error("cleanup failed")
    }
  }

  @GraphExtension.Factory
  fun interface Factory {
    fun create(@Provides name: String): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph : CloseableGraph {
  @OnGraphClose
  fun finish(events: Events, name: String) {
    events.values += "grandchild:$name"
  }

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): GrandchildGraph
  }
}

fun box(): String {
  val events = Events()
  val parent = createGraphFactory<ParentGraph.Factory>().create(events)
  val savedFactory = parent.childFactory
  assertFailsWith<IllegalStateException> { savedFactory.create("bad-init") }
  val failed = savedFactory.create("bad-close")
  assertFailsWith<IllegalStateException> { failed.close() }
  failed.close()
  val child = savedFactory.create("child")
  child.grandchildFactory.create()
  val independent = savedFactory.create("independent")
  independent.close()
  parent.close()
  parent.close()
  assertEquals(1, events.values.count { it == "bad-close" })
  assertEquals(1, events.values.count { it == "independent" })
  assertEquals(0, events.values.count { it == "bad-init" })
  assertEquals(1, events.values.count { it == "child" })
  assertEquals(1, events.values.count { it == "parent" })
  assertTrue(events.values.indexOf("grandchild:child") < events.values.indexOf("child"))
  assertTrue(events.values.indexOf("child") < events.values.indexOf("parent"))
  assertFailsWith<IllegalStateException> { savedFactory.create("late") }
  assertFailsWith<IllegalStateException> { child.grandchildFactory.create() }

  val failedEvents = Events()
  val failedParent = createGraphFactory<ParentGraph.Factory>().create(failedEvents)
  failedParent.childFactory.create("bad-close")
  assertFailsWith<IllegalStateException> { failedParent.close() }
  failedParent.close()
  assertEquals(1, failedEvents.values.count { it == "bad-close" })
  assertFalse("parent" in failedEvents.values)
  return "OK"
}
