// ENABLE_SUSPEND_PROVIDERS

private val events = mutableListOf<String>()
private lateinit var parent: Parent

@GraphExtension
interface Child : SuspendCloseableGraph {
  @OnGraphClose suspend fun stop() {
    events += "child started"
    // The parent finds this child still closing. A close callback never waits, so this returns.
    parent.close()
    events += "child finished"
  }
}

@DependencyGraph
interface Parent : SuspendCloseableGraph {
  val child: Child

  @OnGraphClose fun stop() {
    events += "parent"
  }
}

fun box(): String = runBlocking {
  parent = createGraph<Parent>()
  parent.child.close()
  val expected = listOf("child started", "parent", "child finished")
  if (events == expected) "OK" else "Fail: $events"
}
