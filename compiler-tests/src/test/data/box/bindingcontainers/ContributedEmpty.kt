// https://github.com/ZacSweers/metro/issues/743
// MODULE: lib
@ContributesTo(AppScope::class)
@BindingContainer
interface EmptyContainer

interface LibDep

@ContributesBinding(AppScope::class)
@Inject
class LibDepImpl : LibDep

// MODULE: main(lib)
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val dep: LibDep

  @DependencyGraph.Factory
  interface Factory {
    fun create(): AppGraph
  }
}

fun box(): String {
  // We mainly want to check that the empty `EmptyContainer` doesn't result in a failure
  val graph = createGraphFactory<AppGraph.Factory>().create()
  assertNotNull(graph.dep)
  return "OK"
}
