// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// The included graph builds its set from its own contributions. To the including graph, that's an
// accessor that returns the whole set.
@DependencyGraph
interface IncludedGraph {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides @IntoSet fun provideIncluded(): String = "included"

  @Provides @IntoMap @StringKey("included") fun provideIncludedInt(): Int = 1
}

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides @IntoSet fun provideChild(): String = "child"

  @Provides @IntoMap @StringKey("child") fun provideChildInt(): Int = 2

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes included: IncludedGraph): ExampleGraph
  }
}
