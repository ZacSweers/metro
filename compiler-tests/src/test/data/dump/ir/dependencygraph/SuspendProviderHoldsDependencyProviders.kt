// ENABLE_SUSPEND_PROVIDERS

@SingleIn(AppScope::class)
@Inject
class Config

class Database(val config: Config)

@BindingContainer
object DatabaseProviders {
  @Provides suspend fun provideDatabase(config: Config): Database = Database(config)
}

@DependencyGraph(AppScope::class, bindingContainers = [DatabaseProviders::class])
interface AppGraph {
  val config: Config

  val databaseProvider: SuspendProvider<Database>
}
