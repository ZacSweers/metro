// ENABLE_RUNTIME_TRACING
// ENABLE_SUSPEND_PROVIDERS
// IGNORE_BACKEND: JS_IR

@file:OptIn(ExperimentalMetroApi::class)

import androidx.tracing.Tracer
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface ExampleGraph : SuspendCloseableGraph {
  @Provides suspend fun value(): String = "value"

  @OnGraphInit suspend fun initialize(value: suspend () -> String) { check(value() == "value") }
  @OnGraphClose suspend fun finish(value: suspend () -> String) { error(value()) }

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(@Provides tracer: Tracer): ExampleGraph
  }
}

fun box(): String {
  testMetroTrace {
    runBlocking {
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
  }
  return "OK"
}
