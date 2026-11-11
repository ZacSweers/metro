// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

abstract class MidScope

@GraphExtension
interface Child : CloseableGraph {
  @OnGraphInit suspend fun start() {}
  @GraphExtension.Factory fun interface Factory { suspend fun create(): Child }
}

// FIR reports this accessor on a graph it sees in full. Mid is an extension, so it merges in IR.
@ContributesTo(MidScope::class)
interface ChildAccessor {
  val <!DEPENDENCY_GRAPH_ERROR!>child<!>: Child
}

@GraphExtension(MidScope::class)
interface Mid : CloseableGraph {
  @GraphExtension.Factory fun interface Factory { fun create(): Mid }
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val midFactory: Mid.Factory
}
