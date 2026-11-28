// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface Dependencies {
  val strings: Set<String>
  val ints: Map<String, Int>
}

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Provides @IntoSet fun provideChild(): String = "child"

  @Provides @IntoMap @StringKey("child") fun provideChildInt(): Int = 2

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes dependencies: Dependencies): ExampleGraph
  }
}
