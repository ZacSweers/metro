class Resource {
  var closes = 0
}

private var creations = 0
@SingleIn(AppScope::class) @Inject class Late { init { creations++ } }

@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  val resource: Resource
  val late: Late

  @OnGraphClose fun stop(resource: Resource) {
    resource.closes++
    close()
    error("failed cleanup")
  }

  @DependencyGraph.Factory fun interface Factory {
    fun create(@Provides resource: Resource): AppGraph
  }
}

fun box(): String {
  val resource = Resource()
  val graph = createGraphFactory<AppGraph.Factory>().create(resource)
  check(runCatching { graph.close() }.exceptionOrNull()?.message == "failed cleanup")
  graph.close()
  check(resource.closes == 1)
  check(graph.resource === resource)
  check(creations == 0)
  check(graph.late === graph.late)
  check(creations == 1)
  return "OK"
}
