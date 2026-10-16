// ENABLE_SUSPEND_PROVIDERS
// RENDER_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@DependencyGraph
interface PropertyParent : SuspendCloseableGraph {
  val <!DEPENDENCY_GRAPH_ERROR!>child<!>: InitializingChild
}

@GraphExtension
interface InitializingChild : SuspendCloseableGraph {
  @OnGraphInit suspend fun initialize() {}

  @GraphExtension.Factory
  fun interface Factory {
    suspend fun create(): InitializingChild
  }
}
