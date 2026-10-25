// ENABLE_SUSPEND_PROVIDERS

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphInit suspend fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
}
