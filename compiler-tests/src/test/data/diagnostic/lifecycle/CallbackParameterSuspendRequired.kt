// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

class Database

@DependencyGraph
interface AppGraph : CloseableGraph {
  @Provides suspend fun provideDatabase(): Database = Database()

  @OnGraphInit
  fun initializeDatabase(<!LIFECYCLE_CALLBACK_SUSPEND_REQUIRED, UNSCOPED_LIFECYCLE_PARAMETER!>database: Database<!>) {}

  @DependencyGraph.Factory
  fun interface Factory {
    suspend fun create(): AppGraph
  }
}
