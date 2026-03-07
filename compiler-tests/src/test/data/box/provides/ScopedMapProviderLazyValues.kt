// https://github.com/ZacSweers/metro/issues/1879
// Variant: directly provided Map<K, Provider<Lazy<V>>> (not a multibinding) with scoping
@DependencyGraph(AppScope::class)
interface AppGraph {
  val entryPoint: EntryPoint

  @Provides
  @SingleIn(AppScope::class)
  fun providesMap(): Map<String, Provider<Lazy<Int>>> {
    return emptyMap()
  }
}

@Inject class EntryPoint(val map: Map<String, Provider<Lazy<Int>>>)

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.entryPoint.map, graph.entryPoint.map)
  return "OK"
}
