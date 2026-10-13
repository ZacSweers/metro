// A closeable parent closes its closeable graph extensions before running its own close
// callbacks. Graph extensions closed directly are no longer tracked, and closed parents can't
// create new ones.

val events = mutableListOf<String>()

@GraphExtension
interface UserGraph : CloseableGraph {
  val name: String

  @OnGraphClose
  fun closeUser(name: String) {
    events += "close $name"
  }

  @GraphExtension.Factory
  fun interface Factory {
    fun create(@Provides name: String): UserGraph
  }
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val userGraphFactory: UserGraph.Factory

  @OnGraphClose
  fun closeApp() {
    events += "close app"
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val factory = appGraph.userGraphFactory
  val first = factory.create("first")
  val second = factory.create("second")

  first.close()
  assertEquals(listOf("close first"), events)

  appGraph.close()
  assertEquals(listOf("close first", "close second", "close app"), events)

  // Closing a graph extension again does nothing
  second.close()
  assertEquals(3, events.size)

  val error = assertFailsWith<IllegalStateException> { factory.create("third") }
  assertEquals("Cannot create a graph extension of AppGraph because it is closed.", error.message)
  return "OK"
}
