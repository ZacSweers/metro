// https://github.com/ZacSweers/metro/issues/1879
@DependencyGraph(AppScope::class)
interface AppGraph {
  val entryPoint: EntryPoint

  @Provides
  @SingleIn(AppScope::class)
  fun providesMap(): Map<String, String> {
    return emptyMap()
  }
}

@Inject class EntryPoint(val map: Map<String, String>)

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.entryPoint.map, graph.entryPoint.map)
  return "OK"
}
