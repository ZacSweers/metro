// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// An @AssistedInject target with a suspend MEMBER dep and a plain (non-suspend) constructor. The
// nested suspend impl injects members synchronously and cannot await a suspend binding, so this is
// an error rather than silently skipped injection.

class Database(val value: String)

@AssistedInject
class AccountCreator(@Assisted val region: String) {
  @Inject fun injectDatabase(<!METRO_ERROR!>database: Database<!>) = Unit

  @AssistedFactory
  interface Factory {
    fun create(region: String): AccountCreator
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: AccountCreator.Factory

  @Provides suspend fun provideDatabase(): Database = Database("db")
}
