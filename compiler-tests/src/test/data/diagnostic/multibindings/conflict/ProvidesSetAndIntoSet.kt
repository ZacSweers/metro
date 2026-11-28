// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  val strings: Set<String>

  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet fun provideChild(): String = "child"
}
