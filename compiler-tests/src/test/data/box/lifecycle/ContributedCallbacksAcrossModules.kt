// Callbacks contributed from another module run in the graph that merges them.

// MODULE: lib
val events = mutableListOf<String>()

@ContributesTo(AppScope::class)
interface LibLifecycle {
  @OnGraphInit
  fun startLib(name: String) {
    events += "start $name"
  }

  @OnGraphClose
  fun stopLib() {
    events += "stop"
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph : CloseableGraph {
  @Provides fun provideName(): String = "lib"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.close()
  assertEquals(listOf("start lib", "stop"), events)
  return "OK"
}
