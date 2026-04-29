// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @Provides fun provideAny(): Any = Any()
  val any: Any
  val <!MISSING_BINDING!>int<!>: Int
}
