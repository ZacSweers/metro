// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@GraphExtension interface SuspendChild : SuspendCloseableGraph

@DependencyGraph
interface <!DEPENDENCY_GRAPH_ERROR!>SyncParent<!> : CloseableGraph {
  val child: SuspendChild
}

// A graph that isn't closeable doesn't track its extensions. Whoever creates one closes it.
@DependencyGraph
interface PlainParent {
  val child: SuspendChild
}

class ChildScope

@ContributesTo(ChildScope::class) interface CloseableChild : CloseableGraph

@GraphExtension(ChildScope::class)
interface Child {
  @GraphExtension.Factory fun interface Factory { fun create(): Child }
}

@DependencyGraph interface FactoryParent : Child.Factory

@DependencyGraph interface FactoryAccessorParent {
  val factory: Child.Factory
}

// Closing Root can't reach Leaf through Middle, so Middle has to be closeable too.
@GraphExtension interface Leaf : CloseableGraph

@GraphExtension interface <!DEPENDENCY_GRAPH_ERROR!>Middle<!> {
  val leaf: Leaf
}

@DependencyGraph interface Root : CloseableGraph {
  val middle: Middle
}

// The same goes for a contributed closeable extension under a contributed factory.
@GraphExtension interface <!DEPENDENCY_GRAPH_ERROR!>FactoryMiddle<!> : Child.Factory

@DependencyGraph interface FactoryRoot : SuspendCloseableGraph {
  val middle: FactoryMiddle
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
