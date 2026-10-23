// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// METRO_JVM_ONLY

@DependencyGraph
interface <!DEPENDENCY_GRAPH_ERROR!>AppGraph<!> : CloseableGraph {
  @OnGraphInit suspend fun start() {}
}
