// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@Inject class Resource

@DependencyGraph
interface InitializedGraph : CloseableGraph {
  @OnGraphInit
  fun initialize(provider: suspend () -> Resource, lazy: SuspendLazy<Resource>) {}

  @OnGraphClose
  fun cleanup(provider: suspend () -> Resource, lazy: SuspendLazy<Resource>) {}
}

@DependencyGraph
interface CloseOnlyGraph : CloseableGraph {
  @OnGraphClose
  fun cleanup(<!CLOSE_ONLY_BINDING!>provider: suspend () -> Resource<!>, <!CLOSE_ONLY_BINDING!>lazy: SuspendLazy<Resource><!>) {}
}
