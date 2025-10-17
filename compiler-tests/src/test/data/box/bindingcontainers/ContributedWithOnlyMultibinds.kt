// https://github.com/ZacSweers/metro/issues/744
// MODULE: lib
interface MultiboundType

@ContributesTo(AppScope::class)
@BindingContainer
interface MultibindingContainer {
  @Multibinds(allowEmpty = true)
  fun provideMultibinding(): Set<MultiboundType>
}

interface LibDep

@ContributesBinding(AppScope::class)
@Inject
class LibDepImpl : LibDep

// MODULE: main(lib)
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val dep: LibDep
  val multibinding: Set<MultiboundType>

  @DependencyGraph.Factory
  interface Factory {
    fun create(): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create()
  assertNotNull(graph.dep)
  // We mainly want to check that `MultibindingContainer` can provide only a multibinding declaration
  assertNotNull(graph.multibinding)
  return "OK"
}
