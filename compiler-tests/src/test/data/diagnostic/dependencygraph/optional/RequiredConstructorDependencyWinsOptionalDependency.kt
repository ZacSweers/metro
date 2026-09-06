// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// OPTIONAL_DEPENDENCY_BEHAVIOR: DEFAULT

// Constructor diagnostics must point to the required parameter in both declaration orders.
interface Missing

@Inject
class OptionalFirst(
  optional: Missing = error("unused"),
  <!MISSING_BINDING!>val required: Missing<!>,
)

@Inject
class RequiredFirst(
  <!MISSING_BINDING!>val required: Missing<!>,
  optional: Missing = error("unused"),
)

@DependencyGraph
interface OptionalFirstGraph {
  val target: OptionalFirst
}

@DependencyGraph
interface RequiredFirstGraph {
  val target: RequiredFirst
}
