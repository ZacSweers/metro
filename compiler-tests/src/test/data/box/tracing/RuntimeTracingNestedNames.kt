// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

object Types {
  class Factory @Inject constructor()
}

object Graphs {
  @DependencyGraph
  interface AppGraph {
    val factory: Types.Factory

    @DependencyGraph.Factory
    interface Factory {
      fun create(@Provides tracer: Tracer): AppGraph
    }
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<Graphs.AppGraph.Factory>().create(tracer)
    graph.factory
    assertInstant(
      name = "Graphs.AppGraph.factory",
      graph = "Graphs.AppGraph",
      path = "Graphs.AppGraph",
      callable = "factory",
      type = "Types.Factory",
      kind = "Accessor",
    )
    assertTrace(
      name = "Types.Factory",
      graph = "Graphs.AppGraph",
      path = "Graphs.AppGraph",
      type = "Types.Factory",
      kind = "ConstructorInjected",
    )
  }
  return "OK"
}
