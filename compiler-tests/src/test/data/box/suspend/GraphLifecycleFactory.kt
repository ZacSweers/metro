// ENABLE_SUSPEND_PROVIDERS

private var starts = 0

@DependencyGraph
interface AppGraph : CloseableGraph {
  val value: String

  @OnGraphInit fun start() { starts++ }

  @DependencyGraph.Factory fun interface Factory {
    suspend fun create(@Provides value: String): AppGraph
  }
}

fun box(): String {
  val factory = createGraphFactory<AppGraph.Factory>()
  val graph = runBlocking { factory.create("OK") }
  // A suspend factory must finish synchronous init callbacks before it returns the graph.
  check(starts == 1)
  return graph.value
}
