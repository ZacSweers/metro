// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// A missing binding requested as `() -> T` (at a top-level graph accessor) should surface
// the same function-provider hint — `() -> T` is treated as a provider wrapper, so the
// reported missing key is the inner `T`, but the hint reminds the author they either need
// to bind `T` or — if the function was meant to be the bound value — migrate to a named
// fun-interface.

@DependencyGraph
interface AppGraph {
  val <!MISSING_BINDING!>initializer<!>: () -> String
}
