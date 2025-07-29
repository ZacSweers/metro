// https://github.com/ZacSweers/metro/issues/779
interface SomeRepo

@ContributesBinding(AppScope::class, binding = binding<SomeRepo>())
@ContributesBinding(AppScope::class, binding = binding<SomeRepo?>())
@Inject
class SomeRepoImpl : SomeRepo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val repo: SomeRepo
  val nullableRepo: SomeRepo?
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.repo)
  assertNotNull(graph.nullableRepo)
  return "OK"
}