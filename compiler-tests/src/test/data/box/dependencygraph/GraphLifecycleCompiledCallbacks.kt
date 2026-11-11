// MODULE: lib
val events = mutableListOf<String>()

// Final callbacks compiled in another module have no IR body, so they're found by annotation.
abstract class BaseGraph : CloseableGraph {
  @OnGraphInit fun start(value: Int) {
    events += "start $value"
  }

  @OnGraphClose fun stop() {
    events += "stop"
  }
}

// MODULE: main(lib)
@DependencyGraph
abstract class AppGraph : BaseGraph() {
  @Provides fun value(): Int = 1
}

fun box(): String {
  createGraph<AppGraph>().close()
  return if (events == listOf("start 1", "stop")) "OK" else "Fail: $events"
}
