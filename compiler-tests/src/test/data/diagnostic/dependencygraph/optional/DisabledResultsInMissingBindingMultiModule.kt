// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DISABLED

// MODULE: lib
@Inject
class Example(val value: String? = null)

// MODULE: main(lib)
@DependencyGraph
interface <!METRO_ERROR!>AppGraph<!> {
  val example: Example
}
