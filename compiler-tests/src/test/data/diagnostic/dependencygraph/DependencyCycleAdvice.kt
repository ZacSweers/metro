// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface <!GRAPH_DEPENDENCY_CYCLE!>CycleGraph<!> {
  val first: First
}

@Inject class First(second: Second)

@Inject class Second(first: First)
