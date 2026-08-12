// Regression test for https://github.com/ZacSweers/metro/discussions/2644

// MODULE: lib
abstract class UserRepository {
  abstract val mode: String
}

@Origin(UserRepository::class)
@Inject
@ContributesBinding(AppScope::class)
class UserRepository_Impl : UserRepository() {
  override val mode = "real"
}

// MODULE: main(lib)
@Inject
@ContributesBinding(AppScope::class)
class FakeUserRepository : UserRepository() {
  override val mode = "fake"
}

@DependencyGraph(AppScope::class, excludes = [UserRepository::class])
interface AppGraph {
  val repo: UserRepository
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("fake", graph.repo.mode)
  return "OK"
}
