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

// Extension spans use the extension's name, not its generated class.
@GraphExtension interface ChildGraph : CloseableGraph {
  @OnGraphInit fun start() {}
  @OnGraphClose fun stop() {}
  @GraphExtension.Factory fun interface Factory { fun create(): ChildGraph }
}

@DependencyGraph interface ParentGraph : ChildGraph.Factory {
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides tracer: Tracer): ParentGraph }
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
  testMetroTrace {
    val child = createGraphFactory<ParentGraph.Factory>().create(tracer).create()
    child.close()
    assertInstant(
      name = "ParentGraph.create",
      graph = "ParentGraph",
      path = "ParentGraph",
      callable = "create",
      type = "ChildGraph",
      kind = "Accessor",
    )
    fun span(name: String, kind: String) {
      assertTrace(name = "ChildGraph.$name", graph = "ChildGraph", path = "ParentGraph/ChildGraph", type = "ChildGraph.$name", kind = kind)
    }
    span("init", "GraphInit")
    span("start", "OnGraphInit")
    span("close", "GraphClose")
    span("stop", "OnGraphClose")
  }
  return "OK"
}
