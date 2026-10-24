// RENDER_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

interface Base {
  @OnGraphInit fun initialize() {}
  @OnGraphClose fun finish() {}
}

@DependencyGraph
interface ExampleGraph : Base, CloseableGraph {
  override fun <!GRAPH_LIFECYCLE_ERROR!>initialize<!>() {}
  @OnGraphClose fun <!GRAPH_LIFECYCLE_ERROR!>abstractCallback<!>()
  @OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>nonUnit<!>(): String = "invalid"
  override fun <!GRAPH_LIFECYCLE_ERROR!>close<!>() {}

  @OnGraphClose
  override fun finish() {
    <!GRAPH_LIFECYCLE_ERROR!>super.finish()<!>
  }
}

fun invoke(graph: ExampleGraph) {
  <!GRAPH_LIFECYCLE_ERROR!>graph.finish()<!>
  val reference = <!GRAPH_LIFECYCLE_ERROR!>graph::finish<!>
  reference()
}

@OnGraphInit fun <!GRAPH_LIFECYCLE_ERROR!>topLevel<!>() {}
