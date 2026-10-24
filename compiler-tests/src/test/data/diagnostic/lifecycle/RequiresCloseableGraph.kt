// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

@ContributesTo(AppScope::class)
interface LifecycleContribution {
  @OnGraphInit fun initialize() {}
}

@DependencyGraph(AppScope::class)
interface <!GRAPH_LIFECYCLE_ERROR!>ExampleGraph<!>
