// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface HttpClient

@DependencyGraph
interface AppGraph {
  @OptionalBinding fun optionalHttpClient(): HttpClient = error("unused")
  val <!MISSING_BINDING!>requiredHttpClient<!>: HttpClient
}
