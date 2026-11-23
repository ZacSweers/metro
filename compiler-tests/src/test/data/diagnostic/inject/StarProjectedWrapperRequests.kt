// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface Graph {
  val <!MISSING_BINDING!>lazy<!>: Lazy<*>
  val <!MISSING_BINDING!>nested<!>: Lazy<Provider<*>>
  val <!MISSING_BINDING!>function<!>: Function0<*>
  val <!MISSING_BINDING!>injector<!>: MembersInjector<*>
}
