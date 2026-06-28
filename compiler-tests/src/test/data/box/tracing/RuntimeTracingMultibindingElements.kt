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
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )

    assertEquals(mapOf("one" to 1), graph.intsByName)
    assertInstant(
      name = "AppGraph.intsByName",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "intsByName",
      type = "Map<String, Int>",
      kind = "Accessor",
    )
    assertTrace(
      name = "Map<String, Int>",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Map<String, Int>",
      kind = "Multibinding",
    )
    assertTrace(
      name = "Int",
      graph = "AppGraph",
      path = "AppGraph",
      type = "Int",
      kind = "Provided",
    )
  }
  return "OK"
}
