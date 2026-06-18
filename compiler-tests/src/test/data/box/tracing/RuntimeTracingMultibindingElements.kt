// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.IntoMap
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.StringKey
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface AppGraph {
  val strings: Set<String>
  val intsByName: Map<String, Int>

  @Provides @IntoSet fun provideString(): String = "string"

  @Provides @IntoMap @StringKey("one") fun provideInt(): Int = 1

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals(setOf("string"), graph.strings)
    assertEvent(
      name = "Set<String>",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "Set<String>",
      kind = "Multibinding",
    )
    assertEvent(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "String",
      kind = "Provided",
    )

    assertEquals(mapOf("one" to 1), graph.intsByName)
    assertEvent(
      name = "Map<String, Int>",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "Map<String, Int>",
      kind = "Multibinding",
    )
    assertEvent(
      name = "Int",
      graph = "AppGraph",
      path = "AppGraph",
      binding = "Int",
      kind = "Provided",
    )
  }
  return "OK"
}
