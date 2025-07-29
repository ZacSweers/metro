// https://github.com/ZacSweers/metro/issues/835
// MODULE: lib
interface MultiboundType

@ContributesTo(AppScope::class)
@BindingContainer
interface MultibindingContainer {
  @Multibinds(allowEmpty = true)
  fun provideMultibinding(): Set<MultiboundType>
}

abstract class ChildScope

@ContributesGraphExtension(ChildScope::class)
interface ChildGraph {
  val multibinding: Set<MultiboundType>

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

// MODULE: main(lib)
@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph {

  @DependencyGraph.Factory
  interface Factory {
    fun create(): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create()
  val childGraph = graph.createChild()
  assertNotNull(childGraph.multibinding)
  return "OK"
}
