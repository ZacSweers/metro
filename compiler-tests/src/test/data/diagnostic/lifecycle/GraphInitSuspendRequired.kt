// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

class Database

@DependencyGraph
interface <!GRAPH_INIT_SUSPEND_REQUIRED!>NoFactoryGraph<!> : CloseableGraph {
  @OnGraphInit suspend fun start() {}
}

@DependencyGraph
interface SyncFactoryGraph : CloseableGraph {
  @Provides suspend fun provideDatabase(): Database = Database()

  @OnGraphInit suspend fun migrate(<!UNSCOPED_LIFECYCLE_PARAMETER!>database: Database<!>) {}

  @DependencyGraph.Factory
  fun interface Factory {
    fun <!GRAPH_INIT_SUSPEND_REQUIRED!>create<!>(): SyncFactoryGraph
  }
}
