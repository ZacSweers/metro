// Verify that scoped bindings work correctly with contribution providers.
// The same instance should be returned for the same scope.

interface Repository

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class RepositoryImpl : Repository

@DependencyGraph(AppScope::class)
interface AppGraph {
  val repo1: Repository
  val repo2: Repository
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.repo1, graph.repo2)
  return "OK"
}
