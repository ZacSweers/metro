// ENABLE_SUSPEND_PROVIDERS
// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph interface AppGraph : CloseableGraph {
  @OnGraphInit fun start() {}
  @OnGraphClose fun stop() {}
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides tracer: Tracer): AppGraph }
}

@DependencyGraph interface AsyncGraph : SuspendCloseableGraph {
  @OnGraphInit suspend fun start() {}
  @OnGraphClose suspend fun stop() {}
  @DependencyGraph.Factory fun interface Factory { suspend fun create(@Provides tracer: Tracer): AsyncGraph }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    graph.close()
    graph.close()
    fun span(name: String, kind: String) {
      assertTrace(name = "AppGraph.$name", graph = "AppGraph", path = "AppGraph", type = "AppGraph.$name", kind = kind)
    }
    span("init", "GraphInit")
    span("start", "OnGraphInit")
    span("close", "GraphClose")
    span("stop", "OnGraphClose")
  }
  testMetroTrace {
    runBlocking {
      val graph = createGraphFactory<AsyncGraph.Factory>().create(tracer)
      graph.close()
      graph.close()
    }
    fun span(name: String, kind: String) {
      assertTrace(name = "AsyncGraph.$name", graph = "AsyncGraph", path = "AsyncGraph", type = "AsyncGraph.$name", kind = kind)
    }
    span("init", "GraphInit")
    span("start", "OnGraphInit")
    span("close", "GraphClose")
    span("stop", "OnGraphClose")
  }
  return "OK"
}
