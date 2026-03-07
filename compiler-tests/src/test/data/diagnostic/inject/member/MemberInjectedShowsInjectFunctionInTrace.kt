// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// Test that missing bindings for member-injected properties show the property in the trace
// rather than just the inject() function

class FeatureScreen {
  @Inject
  lateinit var <!METRO_ERROR!>dependency<!>: Dependency

  @ContributesTo(Unit::class)
  interface ServiceProvider
}

interface Dependency

@DependencyGraph(Unit::class)
interface FeatureGraph {
  fun inject(screen: FeatureScreen)
}
