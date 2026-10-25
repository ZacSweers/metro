// ENABLE_SUSPEND_PROVIDERS

private var started = false

// The graph's own metroInit() accessor keeps its name, so Metro picks another for its init function.
@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides fun value(): String = "value"

  suspend fun metroInit(): String

  @OnGraphInit suspend fun start() { started = true }

  @DependencyGraph.Factory fun interface Factory { suspend fun create(): AppGraph }
}

fun box(): String = runBlocking {
  val graph = createGraphFactory<AppGraph.Factory>().create()
  check(started)
  check(graph.metroInit() == "value")
  "OK"
}
