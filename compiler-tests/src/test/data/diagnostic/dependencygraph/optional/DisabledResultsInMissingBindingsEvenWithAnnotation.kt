// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DISABLED

@Inject
class Example(<!ANNOTATION_WILL_BE_APPLIED_ALSO_TO_PROPERTY_OR_FIELD!>@OptionalDependency<!> <!METRO_ERROR!>val value: String? = null<!>)

@DependencyGraph
interface AppGraph {
  val example: Example
}
