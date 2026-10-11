// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@GraphExtension
interface DirectChild : CloseableGraph {
  @OnGraphInit suspend fun start() {}
}

@GraphExtension
interface FactoryChild : CloseableGraph {
  @OnGraphInit suspend fun start() {}

  @GraphExtension.Factory
  fun interface Factory {
    fun <!GRAPH_INIT_SUSPEND_REQUIRED!>create<!>(): FactoryChild
  }
}

@DependencyGraph
interface AppGraph : CloseableGraph {
  val <!GRAPH_INIT_SUSPEND_REQUIRED!>directChild<!>: DirectChild
  val factoryChildFactory: FactoryChild.Factory
}
