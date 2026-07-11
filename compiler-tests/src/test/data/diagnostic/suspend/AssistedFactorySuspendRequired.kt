// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// When an @AssistedInject target consumes suspend bindings in this graph, the corresponding
// @AssistedFactory SAM must be declared `suspend` so the generated impl can await them.

class AccountCreator
<!SUGGEST_CLASS_INJECTION!>@AssistedInject<!>
constructor(@Assisted val region: String, val database: Int) {
  @AssistedFactory
  interface Factory {
    fun <!METRO_ERROR!>create<!>(region: String): AccountCreator
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: AccountCreator.Factory

  @Provides suspend fun provideDatabase(): Int = 7
}
