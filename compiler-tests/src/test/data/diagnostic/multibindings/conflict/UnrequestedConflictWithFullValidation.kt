// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// ENABLE_FULL_BINDING_GRAPH_VALIDATION

// Nothing requests the set, but full validation checks every binding.
@DependencyGraph
interface <!MULTIBINDING_CONFLICT!>ExampleGraph<!> {
  @Provides fun provideStrings(): Set<String> = setOf("whole")

  @Provides @IntoSet fun provideChild(): String = "child"
}
