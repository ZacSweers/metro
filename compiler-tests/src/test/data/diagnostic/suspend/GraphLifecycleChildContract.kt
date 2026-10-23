// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY
// ENABLE_SUSPEND_PROVIDERS

@GraphExtension interface <!DEPENDENCY_GRAPH_ERROR!>Child<!> : SuspendCloseableGraph

@DependencyGraph
interface Parent : CloseableGraph {
  val child: Child
}
