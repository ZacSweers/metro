// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  @Provides @IntoSet fun provideNumber(): Number = 3
  val any: Set<Number>
  val <!MISSING_BINDING!>int<!>: Int
}
