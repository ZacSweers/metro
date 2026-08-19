// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

interface HttpClient

// Same as RequiredAccessorWinsOptionalAccessor with the declaration order flipped. The required
// request must win regardless of which accessor registers the key first.
@DependencyGraph
interface AppGraph {
  val <!MISSING_BINDING!>requiredHttpClient<!>: HttpClient

  @OptionalBinding fun optionalHttpClient(): HttpClient = error("unused")
}
