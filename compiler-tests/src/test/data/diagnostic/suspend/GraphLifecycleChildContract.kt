// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR

@GraphExtension interface SuspendChild : SuspendCloseableGraph

@DependencyGraph
interface <!DEPENDENCY_GRAPH_ERROR!>SyncParent<!> : CloseableGraph {
  val child: SuspendChild
}

@DependencyGraph
interface <!DEPENDENCY_GRAPH_ERROR!>PlainParent<!> {
  val child: SuspendChild
}

class ChildScope

@ContributesTo(ChildScope::class) interface CloseableChild : CloseableGraph

@GraphExtension(ChildScope::class)
interface Child {
  @GraphExtension.Factory fun interface Factory { fun create(): Child }
}

@DependencyGraph interface <!DEPENDENCY_GRAPH_ERROR!>FactoryParent<!> : Child.Factory

@DependencyGraph interface <!DEPENDENCY_GRAPH_ERROR!>FactoryAccessorParent<!> {
  val factory: Child.Factory
}

// Returning an existing child leaves it with its original owner.
@DependencyGraph interface ReceivesChild {
  val child: SuspendChild
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides child: SuspendChild): ReceivesChild }
}

@DependencyGraph interface ReceivesFactory {
  val factory: Child.Factory
  @DependencyGraph.Factory fun interface Factory { fun create(@Provides factory: Child.Factory): ReceivesFactory }
}
