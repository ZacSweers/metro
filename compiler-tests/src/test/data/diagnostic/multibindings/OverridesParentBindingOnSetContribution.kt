// RUN_PIPELINE_TILL: FIR2IR
// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph {
  val items: Set<String>

  @Provides
  @IntoSet
  @OverridesParentBinding
  fun <!OVERRIDES_PARENT_BINDING_ON_SET_CONTRIBUTION!>provideFirst<!>(): String = "a"
}
