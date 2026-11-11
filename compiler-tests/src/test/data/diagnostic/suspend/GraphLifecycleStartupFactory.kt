// ENABLE_SUSPEND_PROVIDERS
// RENDER_DIAGNOSTICS_FULL_TEXT

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphInit suspend fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
}
