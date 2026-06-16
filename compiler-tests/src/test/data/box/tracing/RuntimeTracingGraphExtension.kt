// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

@GraphExtension
interface ChildGraph {
  val count: Int

  @Provides fun provideCount(): Int = 3

  @GraphExtension.Factory
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@DependencyGraph
interface AppGraph : ChildGraph.Factory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals(3, graph.createChildGraph().count)
    assertEvent(
      name = "Int",
      graph = "ChildGraph",
      path = "AppGraph/ChildGraph",
      binding = "Int",
      kind = "Provided",
    )
  }
  return "OK"
}
