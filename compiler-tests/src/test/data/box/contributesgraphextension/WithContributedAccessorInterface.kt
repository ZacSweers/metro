// https://github.com/ZacSweers/metro/issues/1074
// Use-case: ensure that the graph extension class has its supertypes updated with contributed
// accessor interfaces, not just the implementation used under the hood.
// MODULE: lib
package test

abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@ContributesTo(ChildScope::class)
interface FooProvider {
  val foo: Foo
}

interface Foo

// MODULE: main(lib)
package test

@DependencyGraph(AppScope::class)
interface AppGraph

@Inject
@ContributesBinding(ChildScope::class)
class RealFoo : Foo

fun box(): String {
  val graph = createGraph<AppGraph>()

  assertTrue(ChildGraph::class.java.interfaces.isNotEmpty())
  // We should be able to find the factory method by any contributed interface
  val factoryMethod = graph.javaClass.methods.find {
    FooProvider::class.java.isAssignableFrom(it.returnType)
  }
  assertNotNull(factoryMethod)
  assertEquals(ChildGraph::class.java, factoryMethod.returnType)
  val childGraph = factoryMethod.invoke(graph) as FooProvider
  assertTrue(childGraph.foo is RealFoo)
  return "OK"
}
