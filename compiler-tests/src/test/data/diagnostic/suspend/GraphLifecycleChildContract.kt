// ENABLE_SUSPEND_PROVIDERS

@GraphExtension interface Child : SuspendCloseableGraph

@DependencyGraph
interface Parent : CloseableGraph {
  val <!DEPENDENCY_GRAPH_ERROR!>child<!>: Child
}
