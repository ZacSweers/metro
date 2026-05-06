// Verify that a @ContributesBinding + @Inject class whose factory is skipped (because
// contribution providers handle construction) doesn't crash with "Transforming after locked!"
// when the impl type is looked up during graph resolution.

interface Repository {
  fun value(): String
}

@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl(val input: String) : Repository {
  override fun value(): String = input
}

@Inject class Consumer(val repo: RepositoryImpl = RepositoryImpl("default"))

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer

  @Provides fun string(): String = "OK"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  // Consumer.repo uses default value since RepositoryImpl has no factory with contribution
  // providers
  return "OK"
}
