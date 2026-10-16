// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

@file:OptIn(ExperimentalMetroApi::class)

import androidx.tracing.Tracer
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph(AppScope::class)
interface ExampleGraph : CloseableGraph {
  @Provides @SingleIn(AppScope::class) fun value(): String = "value"

  @OnGraphInit fun initialize(value: String) { check(value == "value") }
  @OnGraphClose fun finish(value: String) { error(value) }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides tracer: Tracer): ExampleGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<ExampleGraph.Factory>().create(tracer)
    assertTrace("initialize", "ExampleGraph", "ExampleGraph", "initialize", kind = "Graph Initialization")
    assertTrace("ExampleGraph.initialize", "ExampleGraph", "ExampleGraph", "ExampleGraph.initialize", kind = "OnGraphInit")
    assertTrace("String", "ExampleGraph", "ExampleGraph", "String", kind = "Provided")
    assertFailsWith<IllegalStateException> { graph.close() }
    graph.close()
    assertTrace("close", "ExampleGraph", "ExampleGraph", "close", kind = "Graph Close")
    assertTrace("ExampleGraph.finish", "ExampleGraph", "ExampleGraph", "ExampleGraph.finish", kind = "OnGraphClose")
    assertTrace("String", "ExampleGraph", "ExampleGraph", "String", kind = "Provided")
    tracer.trace(category = "test", name = "still open") {}
  }
  return "OK"
}
