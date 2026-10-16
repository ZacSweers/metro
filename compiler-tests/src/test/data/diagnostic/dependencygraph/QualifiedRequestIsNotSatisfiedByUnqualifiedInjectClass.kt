// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// An @Inject class with no qualifier only satisfies the unqualified key.

@Qualifier
annotation class Marker

@Inject
class Tracker

@DependencyGraph
interface AppGraph {
  @Marker val <!MISSING_BINDING!>tracker<!>: Tracker
}
