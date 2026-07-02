// `@SuspendAware @AssistedInject` — the assisted factory's SAM `create` is `suspend` because
// the underlying factory's `invoke()` is suspend.

@SuspendAware
@Inject
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
  assertNotNull(graph.factory)
  return "OK"
}
