// MODULE: lib
abstract class RootScope
abstract class ParentScope
abstract class ChildScope

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val value: String

  @GraphExtension.Factory
  @ContributesTo(ParentScope::class)
  interface Factory {
    fun createChild(@Provides value: String): ChildGraph
  }
}

@Inject
class ChildCoordinator(private val childGraphFactory: ChildGraph.Factory) {
  fun createChild(value: String): ChildGraph = childGraphFactory.createChild(value)
}

@GraphExtension(ParentScope::class)
interface ParentGraph {
  val childCoordinator: ChildCoordinator

  @GraphExtension.Factory
  @ContributesTo(RootScope::class)
  interface Factory {
    fun createParent(): ParentGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(RootScope::class)
interface RootGraph

fun box(): String {
  val parentGraph = createGraph<RootGraph>().createParent()
  val childGraph = parentGraph.childCoordinator.createChild("expected")
  assertEquals("expected", childGraph.value)
  return "OK"
}
