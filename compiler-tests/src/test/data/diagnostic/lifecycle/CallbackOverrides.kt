// RENDER_DIAGNOSTICS_FULL_TEXT

interface Callbacks {
  @OnGraphInit fun start() {}

  @OnGraphClose fun stop() {}
}

@DependencyGraph
interface AppGraph : Callbacks, CloseableGraph {
  override fun <!GRAPH_LIFECYCLE_ERROR!>start<!>() {}

  @OnGraphInit override fun <!GRAPH_LIFECYCLE_ERROR!>stop<!>() {}
}

@DependencyGraph
interface ValidGraph : Callbacks, CloseableGraph {
  @OnGraphInit override fun start() {}
}
