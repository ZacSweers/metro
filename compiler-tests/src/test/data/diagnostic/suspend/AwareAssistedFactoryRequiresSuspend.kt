// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT

// When `@AssistedInject` target is `@SuspendAware`, the corresponding `@AssistedFactory` SAM
// must be declared `suspend`.

@SuspendAware
@Inject
class AccountCreator
@AssistedInject
constructor(@Assisted val region: String, val database: Int) {
  @AssistedFactory
  interface Factory {
    <!METRO_ERROR!>fun create(region: String): AccountCreator<!>
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: AccountCreator.Factory

  @Provides suspend fun provideDatabase(): Int = 7
}
