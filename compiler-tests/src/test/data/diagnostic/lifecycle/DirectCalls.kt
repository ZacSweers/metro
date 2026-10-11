// RENDER_DIAGNOSTICS_FULL_TEXT

interface Callbacks {
  @OnGraphInit fun start() {}
}

@DependencyGraph
interface AppGraph : Callbacks, CloseableGraph {
  @OnGraphInit
  override fun start() {
    <!LIFECYCLE_CALLBACK_DIRECT_CALL!>super.start()<!>
  }

  @OnGraphClose fun stop() {}
}

fun useGraph(graph: AppGraph) {
  <!LIFECYCLE_CALLBACK_DIRECT_CALL!>graph.start()<!>
  <!LIFECYCLE_CALLBACK_DIRECT_CALL!>graph.stop()<!>
  val bound = <!LIFECYCLE_CALLBACK_DIRECT_CALL!>graph::stop<!>
  val unbound = <!LIFECYCLE_CALLBACK_DIRECT_CALL!>AppGraph::stop<!>
}
