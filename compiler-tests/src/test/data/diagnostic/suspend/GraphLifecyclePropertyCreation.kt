// ENABLE_SUSPEND_PROVIDERS

// A property accessor can't suspend, so it can't create a child whose init suspends.
@GraphExtension interface Child : CloseableGraph {
  @OnGraphInit suspend fun <!DEPENDENCY_GRAPH_ERROR!>start<!>() {}
}
@DependencyGraph interface Parent : CloseableGraph {
  val child: Child
}
