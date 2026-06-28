// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface AppGraph {
  val strings: Set<String>

  @Multibinds(allowEmpty = true) fun bindStrings(): Set<String>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals(emptySet<String>(), graph.strings)
    assertInstant(
      name = "AppGraph.strings",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "strings",
      type = "Set<String>",
      kind = "Accessor",
    )
    assertTrace(
      name = "Set<String>",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Set<String>",
      kind = "Multibinding",
    )
  }
  return "OK"
}
