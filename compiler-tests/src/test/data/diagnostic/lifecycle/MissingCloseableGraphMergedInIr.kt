// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// FIR can't see callbacks that are only merged in IR, so IR reports this.

@ContributesTo(AppScope::class)
interface Callbacks {
  @OnGraphInit fun start() {}
}

@MergeContributionsInIr @DependencyGraph(AppScope::class) interface <!MISSING_CLOSEABLE_GRAPH!>AppGraph<!>
