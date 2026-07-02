// ENABLE_RUNTIME_TRACING
// IGNORE_BACKEND: JS_IR

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.trace.internal.testMetroTrace

@DependencyGraph
interface SourceGraph {
  val message: String

  @Provides fun provideMessage(): String = "source"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): SourceGraph
  }
}

@GraphExtension
interface ChildGraph {
  val sourceMessage: String

  @Provides fun provideSourceMessage(sourceGraph: Provider<SourceGraph>): String {
    return sourceGraph().message
  }

  @GraphExtension.Factory
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@DependencyGraph
interface AppGraph : ChildGraph.Factory {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer, @Includes sourceGraph: SourceGraph): AppGraph
  }
}

fun box(): String {
  testMetroTrace {
    val sourceGraph = createGraphFactory<SourceGraph.Factory>().create(tracer)
    val appGraph = createGraphFactory<AppGraph.Factory>().create(tracer, sourceGraph)

    assertEquals("source", appGraph.createChildGraph().sourceMessage)
    assertInstant(
      name = "AppGraph.createChildGraph",
      graph = "AppGraph",
      path = "AppGraph",
      callable = "createChildGraph",
      type = "ChildGraph",
      kind = "Accessor",
    )
    assertInstant(
      name = "ChildGraph.sourceMessage",
      graph = "ChildGraph",
      path = "AppGraph/ChildGraph",
      callable = "sourceMessage",
      type = "String",
      kind = "Accessor",
    )
    assertTrace(
      name = "String",
      graph = "ChildGraph",
      path = "AppGraph/ChildGraph",
      type = "String",
      kind = "Provided",
    )
    assertTrace(
      name = "SourceGraph",
      graph = "ChildGraph",
      path = "AppGraph/ChildGraph",
      type = "SourceGraph",
      kind = "GraphDependency",
    )
    assertInstant(
      name = "SourceGraph.message",
      graph = "SourceGraph",
      path = "SourceGraph",
      callable = "message",
      type = "String",
      kind = "Accessor",
    )
    assertTrace(
      name = "String",
      graph = "SourceGraph",
      path = "SourceGraph",
      type = "String",
      kind = "Provided",
    )
  }
  return "OK"
}
