// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph
interface MissingFactory : CloseableGraph {
  @OnGraphInit suspend fun <!GRAPH_LIFECYCLE_ERROR!>initialize<!>() {}
}

@DependencyGraph
interface SynchronousFactory : SuspendCloseableGraph {
  @OnGraphInit suspend fun <!GRAPH_LIFECYCLE_ERROR!>initialize<!>() {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(): SynchronousFactory
  }
}

@DependencyGraph
interface SynchronousClose : CloseableGraph {
  @OnGraphClose suspend fun <!GRAPH_LIFECYCLE_ERROR!>finish<!>() {}
}

@DependencyGraph
interface ParentGraph : CloseableGraph {
  val childFactory: ChildGraph.Factory
}

@GraphExtension
interface <!GRAPH_LIFECYCLE_ERROR!>ChildGraph<!> : SuspendCloseableGraph {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ChildGraph
  }
}

