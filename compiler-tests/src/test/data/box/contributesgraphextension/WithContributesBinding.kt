// https://github.com/ZacSweers/metro/issues/776
// MODULE: lib
abstract class ChildScope

@ContributesGraphExtension(ChildScope::class)
interface ChildGraph {

  val foo: Foo

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

interface Foo

// MODULE: main(lib)
@DependencyGraph(AppScope::class, isExtendable = true)
interface AppGraph

@Inject
@ContributesBinding(ChildScope::class)
class RealFoo : Foo

fun box(): String {
  val graph = createGraph<AppGraph>()
  val childGraph = graph.createChild()
  assertTrue(childGraph.foo is RealFoo)
  return "OK"
}