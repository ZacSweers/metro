// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface AppGraph : CloseableGraph {
  val value: String
  @DependencyGraph.Factory fun interface Factory {
    suspend fun create(@Provides value: String): AppGraph
  }
}

fun box(): String {
  val factory = createGraphFactory<AppGraph.Factory>()
  return runBlocking { factory.create("OK").value }
}
