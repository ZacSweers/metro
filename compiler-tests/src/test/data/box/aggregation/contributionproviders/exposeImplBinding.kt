// Verify that @ExposeImplBinding allows the impl type to be directly injected
// even when generateContributionProviders is enabled.

interface Repository {
  fun value(): String
}

@ExposeImplBinding
@ContributesBinding(AppScope::class)
@Inject
class RepositoryImpl(val input: String) : Repository {
  override fun value(): String = input
}

// This class injects the impl type directly — works because of @ExposeImplBinding
@Inject
class Consumer(val repo: RepositoryImpl)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
  @Provides fun string(): String = "OK"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  return graph.consumer.repo.value()
}
