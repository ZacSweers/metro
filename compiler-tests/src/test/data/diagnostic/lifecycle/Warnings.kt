// RUN_PIPELINE_TILL: BACKEND
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@Inject class Unscoped
@Inject @SingleIn(AppScope::class) class Scoped
@Inject class Ordinary

@DependencyGraph(AppScope::class)
interface ExampleGraph : CloseableGraph {
  val ordinary: Ordinary

  @OnGraphClose
  fun finish(<!UNSCOPED_LIFECYCLE_PARAMETER!>unscoped: Unscoped<!>, scoped: Scoped, <!UNSCOPED_LIFECYCLE_PARAMETER!>ordinary: Ordinary<!>) {}

  @Suppress("CLOSE_ONLY_BINDING")
  @OnGraphInit
  fun initialize(deferred: () -> Unscoped, lazy: Lazy<Scoped>) {}
}

@DependencyGraph(AppScope::class)
interface CloseOnlyGraph : CloseableGraph {
  @OnGraphClose
  fun finish(<!CLOSE_ONLY_BINDING!>deferred: () -> Unscoped<!>, <!CLOSE_ONLY_BINDING!>lazy: Lazy<Scoped><!>) {}
}

@DependencyGraph
interface SuppressedGraph : CloseableGraph {
  @Suppress("UNSCOPED_LIFECYCLE_PARAMETER", "CLOSE_ONLY_BINDING")
  @OnGraphClose
  fun finish(value: Unscoped) {}
}
