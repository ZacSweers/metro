// RUN_PIPELINE_TILL: FIR2IR
// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  // OK: @OverridesParentBinding on a @Provides function — site is valid (whether or not it
  // actually overrides anything is checked separately at IR time).
  // No diagnostic expected here.

  // Invalid: not on a @Provides or @Binds.
  @OverridesParentBinding
  fun <!OVERRIDES_PARENT_BINDING_INVALID_SITE!>accessor<!>(): String

  @Provides fun provideMessage(): String = "hi"
}
