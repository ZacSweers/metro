// ENABLE_SUSPEND_PROVIDERS

class Database

class Config

interface Deps {
  val database: SuspendLazy<Database>

  val config: Lazy<Config>
}

@DependencyGraph
interface AppGraph {
  val databaseProvider: SuspendProvider<Database>

  val configProvider: Provider<Config>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes deps: Deps): AppGraph
  }
}
