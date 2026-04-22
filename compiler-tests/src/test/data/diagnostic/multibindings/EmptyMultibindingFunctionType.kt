// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// Empty multibindings whose element type is a zero-arg function should surface a hint
// explaining that `() -> T` is treated as a provider wrapper under function-provider
// mode and can't be bound directly as a top-level value.

@DependencyGraph
interface AppGraph {
  @Multibinds val <!EMPTY_MULTIBINDING!>initializers<!>: Set<() -> Unit>
}
