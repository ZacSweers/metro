// ENABLE_SUSPEND_PROVIDERS

private val events = mutableListOf<String>()

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphClose fun stop() {
    events += "child"
    error("child failed")
  }
}

@DependencyGraph
interface SyncGraph : CloseableGraph {
  val child: Child
  @OnGraphClose fun first() {
    events += "first"
    error("first failed")
  }
  @OnGraphClose fun second() {
    events += "second"
    error("second failed")
  }
  @OnGraphClose fun third() { events += "third" }
}

@DependencyGraph
interface SuspendGraph : SuspendCloseableGraph {
  val child: Child
  @OnGraphClose suspend fun first() {
    events += "first"
    error("first failed")
  }
  @OnGraphClose fun second() { events += "second" }
}

fun box(): String = runBlocking {
  val sync = createGraph<SyncGraph>()
  sync.child
  val syncFailure = checkNotNull(runCatching { sync.close() }.exceptionOrNull())
  // Every step runs. close() throws the first failure with the later ones attached.
  check(events.first() == "child") { events }
  check(events.toSet() == setOf("child", "first", "second", "third")) { events }
  check(syncFailure.message == "child failed")
  val suppressed = syncFailure.suppressedExceptions.map { it.message }.toSet()
  check(suppressed == setOf("first failed", "second failed")) { suppressed }
  // The graph stays closed and doesn't report the failure again.
  sync.close()
  check(events.size == 4)

  events.clear()
  val suspending = createGraph<SuspendGraph>()
  suspending.child
  val suspendFailure = checkNotNull(runCatching { suspending.close() }.exceptionOrNull())
  check(events.first() == "child") { events }
  check(events.toSet() == setOf("child", "first", "second")) { events }
  check(suspendFailure.message == "child failed")
  check(suspendFailure.suppressedExceptions.single().message == "first failed")
  "OK"
}
