// ENABLE_SUSPEND_PROVIDERS

@GraphExtension interface Child : CloseableGraph {
  @OnGraphInit suspend fun start() {}
  @GraphExtension.Factory fun interface Factory { suspend fun create(): Child }
}
@DependencyGraph interface Parent {
  val <!DEPENDENCY_GRAPH_ERROR!>child<!>: Child
}
