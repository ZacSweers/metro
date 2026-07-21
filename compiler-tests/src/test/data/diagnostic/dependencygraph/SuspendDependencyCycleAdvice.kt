// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

class Provided

@Inject class Consumer(provided: Provided)

@DependencyGraph
abstract class <!GRAPH_DEPENDENCY_CYCLE!>DirectCycleGraph<!> {
  abstract suspend fun provided(): Provided

  @Provides suspend fun provideProvided(consumer: Consumer): Provided = Provided()
}

class Leaf

@Inject class First(second: Second, leaf: Leaf)

@Inject class Second(first: First)

@DependencyGraph
abstract class <!GRAPH_DEPENDENCY_CYCLE!>TransitiveCycleGraph<!> {
  abstract suspend fun first(): First

  @Provides suspend fun provideLeaf(): Leaf = Leaf()
}
