// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@file:OptIn(ExperimentalMetroApi::class)

interface Missing

@DependencyGraph
interface ExampleGraph : CloseableGraph {
  @OnGraphClose fun finish(<!MISSING_BINDING!>missing: Missing<!>) {}
}
