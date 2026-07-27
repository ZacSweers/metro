// ENABLE_SUSPEND_PROVIDERS

@Inject class AccountCreator(val database: String, val tlsConnection: Int)

@DependencyGraph
interface ExampleGraph {
  suspend fun accountCreator(): AccountCreator

  @Provides suspend fun provideDatabase(): String = "db"

  @Provides suspend fun provideTls(): Int = 7
}
