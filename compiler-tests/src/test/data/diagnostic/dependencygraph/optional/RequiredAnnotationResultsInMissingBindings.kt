// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: REQUIRE_OPTIONAL_DEPENDENCY

@Inject
class Example(<!METRO_ERROR!>val value: String? = null<!>)

@DependencyGraph
interface AppGraph {
  val example: Example
}
