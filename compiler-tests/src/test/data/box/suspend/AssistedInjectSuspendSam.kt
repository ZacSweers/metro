// ENABLE_SUSPEND_PROVIDERS

// An @AssistedInject class whose non-assisted dep is a suspend binding in this graph. The
// assisted factory's SAM must be declared `suspend` so the impl can await the suspend deps.

class AccountCreator
@AssistedInject
constructor(@Assisted val region: String, val database: Int) {
  @AssistedFactory
  interface Factory {
    suspend fun create(region: String): AccountCreator
  }
}

@DependencyGraph
interface ExampleGraph {
  val factory: AccountCreator.Factory

  @Provides suspend fun provideDatabase(): Int = 7
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val accountCreator = runBlocking { graph.factory.create("us-east-1") }
  assertEquals("us-east-1", accountCreator.region)
  assertEquals(7, accountCreator.database)
  return "OK"
}
