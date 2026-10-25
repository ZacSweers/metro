// ENABLE_SUSPEND_PROVIDERS

private var closed = ""

@DependencyGraph
interface AppGraph : SuspendCloseableGraph {
  @Provides suspend fun message(): String = "OK"
  @OnGraphClose suspend fun cleanup(message: String) { closed += message }
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return runBlocking {
    graph.close()
    graph.close()
    closed
  }
}
