// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

class Database

@DependencyGraph
interface AppGraph : CloseableGraph {
  @OnGraphClose
  fun closeDatabase(<!MISSING_BINDING!>database: Database<!>) {}
}
