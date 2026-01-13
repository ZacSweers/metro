// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// MODULE: main
@DependencyGraph(AppScope::class)
interface AppGraph {
  val <!METRO_ERROR!>any<!>: Any

  @Provides fun int(): Int = 3
  @Provides fun str(): String = "string"
  @Provides fun boolean(): Boolean = true
}
