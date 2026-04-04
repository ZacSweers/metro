// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!MISSING_BINDING!>any<!>: Any

  @Provides fun int(): Int = 3
  @Provides fun str(): String = "string"
  @Provides fun boolean(): Boolean = true
}
