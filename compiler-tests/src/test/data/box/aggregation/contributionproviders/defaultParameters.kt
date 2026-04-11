// MODULE: common
interface Repository {
  fun value(): String
}

class Config(val enabled: Boolean)

fun defaultConfig(): Config = Config(enabled = true)

// MODULE: lib(common)
@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl(val id: String, val config: Config = defaultConfig()) : Repository {
  override fun value(): String = if (config.enabled) id else "FAIL"
}

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val repo: Repository

  @Provides fun provideString(): String = "OK"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return graph.repo.value()
}
