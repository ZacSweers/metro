// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val ints: Map<String, Int>

  @Provides fun provideInts(): Map<String, Int> = mapOf("whole" to 1)

  @Provides @IntoMap @StringKey("child") fun provideChild(): Int = 2
}
