// ENABLE_RUNTIME_TRACING

import androidx.tracing.Tracer
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides

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
  val tracer: Tracer
  val string: String
  val stringProvider: Provider<String>
  @Named("special") val qualifiedString: String

  @Provides fun provideString(): String = "hello"

  @Provides @Named("special") fun provideQualifiedString(): String = "qualified"

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides tracer: Tracer): AppGraph
  }
}
