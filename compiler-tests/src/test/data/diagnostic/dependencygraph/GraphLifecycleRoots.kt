// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
// METRO_JVM_ONLY

interface Missing

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphInit fun start(<!MISSING_BINDING!>missing: Missing<!>) {}
}
