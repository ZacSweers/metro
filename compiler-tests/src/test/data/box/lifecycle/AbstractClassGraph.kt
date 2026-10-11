// Abstract class graphs can declare protected callbacks.

@DependencyGraph
abstract class AppGraph : CloseableGraph {
  val events = mutableListOf<String>()

  @OnGraphInit
  protected fun start(value: String) {
    events += "start $value"
  }

  @OnGraphClose
  fun stop() {
    events += "stop"
  }

  @Provides fun provideValue(): String = "value"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.close()
  assertEquals(listOf("start value", "stop"), graph.events)
  return "OK"
}
