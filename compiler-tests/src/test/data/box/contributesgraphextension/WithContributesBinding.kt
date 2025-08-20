// https://github.com/ZacSweers/metro/issues/776
// MODULE: lib
abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {

  val foo: Foo

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

interface Foo

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
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