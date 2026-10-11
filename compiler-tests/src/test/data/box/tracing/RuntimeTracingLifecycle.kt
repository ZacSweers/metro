// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides fun provideString(): String = "string"

  @OnGraphInit fun start(value: String) {}

  @OnGraphClose fun stop() {}

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertLifecycleTrace(name = "AppGraph init", graph = "AppGraph", path = "AppGraph", phase = "init")
    assertLifecycleTrace(
      name = "@OnGraphInit AppGraph.start",
      graph = "AppGraph",
      path = "AppGraph",
      phase = "init",
      callable = "start",
    )
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )
    graph.close()
    assertLifecycleTrace(
      name = "AppGraph close",
      graph = "AppGraph",
      path = "AppGraph",
      phase = "close",
    )
    assertLifecycleTrace(
      name = "@OnGraphClose AppGraph.stop",
      graph = "AppGraph",
      path = "AppGraph",
      phase = "close",
      callable = "stop",
    )
  }
  return "OK"
}
