// GENERATE_CONTRIBUTION_PROVIDERS: false

// The generated @Binds requests RealRepo with no qualifier. The graph also requests it with the
// class's qualifier.

@Qualifier annotation class Chosen

interface Repo

@Chosen @Inject @ContributesBinding(AppScope::class) class RealRepo : Repo

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Chosen val repo: Repo

  @Chosen val realRepo: RealRepo
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.repo is RealRepo)
  assertNotNull(graph.realRepo)
  return "OK"
}
