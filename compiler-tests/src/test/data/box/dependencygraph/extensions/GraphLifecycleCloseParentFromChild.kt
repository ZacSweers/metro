private val events = mutableListOf<String>()
private lateinit var parent: Parent

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphClose fun stop() {
    events += "child started"
    // The parent finds this child still closing. A close callback never waits, so this returns.
    parent.close()
    events += "child finished"
  }
}

@DependencyGraph
interface Parent : CloseableGraph {
  val child: Child

  @OnGraphClose fun stop() {
    events += "parent"
  }
}

fun box(): String {
  parent = createGraph<Parent>()
  parent.child.close()
  val expected = listOf("child started", "parent", "child finished")
  return if (events == expected) "OK" else "Fail: $events"
}
