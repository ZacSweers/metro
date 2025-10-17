// https://github.com/ZacSweers/metro/issues/734
// MODULE: lib
@ContributesTo(AppScope::class)
@BindingContainer
interface SampleDependency {
  @Binds
  val LibDepImpl.bind: LibDep
}

interface LibDep

@Inject
class LibDepImpl : LibDep

// MODULE: main(lib)
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val dep: AppDep

  @DependencyGraph.Factory
  interface Factory {
    fun create(): AppGraph
  }
}

@Inject
class AppDep(val dep: LibDep)

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create()
  assertNotNull(graph.dep)
  return "OK"
}