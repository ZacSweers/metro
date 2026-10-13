// ENABLE_SUSPEND_PROVIDERS

// A SuspendCloseableGraph's close callbacks can suspend, and it closes graph extensions with
// either kind of cleanup.

val events = mutableListOf<String>()

@GraphExtension
interface SyncChild : CloseableGraph {
  @OnGraphClose
  fun stop() {
    events += "sync child"
  }

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): SyncChild
  }
}

@GraphExtension
interface SuspendChild : SuspendCloseableGraph {
  @OnGraphClose
  suspend fun stop() {
    events += "suspend child"
  }

  @GraphExtension.Factory
  fun interface Factory {
    fun create(): SuspendChild
  }
}

@DependencyGraph
interface AppGraph : SuspendCloseableGraph {
  val syncChildFactory: SyncChild.Factory
  val suspendChildFactory: SuspendChild.Factory

  @OnGraphClose
  suspend fun stop() {
    events += "app"
  }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.syncChildFactory.create()
  graph.suspendChildFactory.create()
  runBlocking { graph.close() }
  assertEquals(listOf("suspend child", "sync child"), events.dropLast(1).sorted())
  assertEquals("app", events.last())
  return "OK"
}
