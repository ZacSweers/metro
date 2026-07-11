// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Two accessors for the same binding, one suspend and one not: BOTH must be validated. The
// non-suspend one errors even though a suspend accessor with the same contextual key exists.

@DependencyGraph
interface ExampleGraph {
  suspend fun value(): String

  val <!METRO_ERROR!>eagerValue<!>: String

  @Provides suspend fun provideString(): String = "hello"
}
