// Verify that scoped bindings work correctly with contribution providers.
// The same instance should be returned for the same scope.

// MODULE: common
interface Repository

// MODULE: lib(common)
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class RepositoryImpl(val id: String) : Repository

// MODULE: main(lib, common)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val repo1: Repository
  val repo2: Repository

  @Provides
  fun provideString(): String = "Hello"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.repo1, graph.repo2)
  return "OK"
}
