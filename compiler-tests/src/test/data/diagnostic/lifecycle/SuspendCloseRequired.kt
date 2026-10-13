// ENABLE_SUSPEND_PROVIDERS
// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

@GraphExtension
interface SuspendChild : SuspendCloseableGraph {
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): SuspendChild
  }
}

@DependencyGraph
interface <!SUSPEND_CLOSE_REQUIRED!>AppGraph<!> : CloseableGraph {
  val suspendChildFactory: SuspendChild.Factory
}

class Database

@DependencyGraph
interface SuspendingParameterGraph : CloseableGraph {
  @Provides suspend fun provideDatabase(): Database = Database()

  @OnGraphClose fun closeDatabase(<!CLOSE_ONLY_BINDING, LIFECYCLE_CALLBACK_SUSPEND_REQUIRED, SUSPEND_CLOSE_REQUIRED, UNSCOPED_LIFECYCLE_PARAMETER!>database: Database<!>) {}
}
