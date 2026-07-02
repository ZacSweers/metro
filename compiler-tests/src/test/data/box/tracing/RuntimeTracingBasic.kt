// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface AppGraph {
  val string: String
  val stringProvider: Provider<String>
  @Named("qualified") val qualifiedStringProvider: Provider<String>

  @Provides fun provideString(): String = "string"

  @Provides @Named("qualified") fun provideQualifiedString(): String = "qualified"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val graph = createGraphFactory<AppGraph.Factory>().create(tracer)
    assertEquals("string", graph.string)
    assertInstant(
      name = "AppGraph.string",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "string",
      type = "String",
      kind = "Accessor",
    )
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )
    assertEquals("string", graph.stringProvider())
    assertInstant(
      name = "AppGraph.stringProvider",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "stringProvider",
      type = "String",
      contextualType = "Provider<String>",
      kind = "Accessor",
    )
    assertTrace(
      name = "String",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      kind = "Provided",
    )
    assertEquals("qualified", graph.qualifiedStringProvider())
    assertInstant(
      name = "AppGraph.qualifiedStringProvider",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "qualifiedStringProvider",
      type = "String",
      contextualType = "Provider<String>",
      qualifier = """@Named("qualified")""",
      kind = "Accessor",
    )
    assertTrace(
      name = """@Named("qualified") String""",
      graph = "AppGraph",
      path = "AppGraph",
      type = "String",
      qualifier = """@Named("qualified")""",
      kind = "Provided",
    )
  }
  return "OK"
}
