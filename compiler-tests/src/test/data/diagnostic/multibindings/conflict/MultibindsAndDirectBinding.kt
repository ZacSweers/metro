// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val strings: Set<String>
  val ints: Map<String, Int>

  @Multibinds(allowEmpty = true) fun declareStrings(): Set<String>

  @Multibinds(allowEmpty = true) fun declareInts(): Map<String, Int>

  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides fun provideInts(): Map<String, Int> = mapOf("whole" to 1)
}
