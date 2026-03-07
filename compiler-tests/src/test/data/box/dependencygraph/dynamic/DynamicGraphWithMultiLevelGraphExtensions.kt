// Tests that dynamic bindings propagate across multiple levels of graph extensions.
// A dynamic binding at the root should be visible in child and grandchild graph extensions.

@DependencyGraph interface RootGraph : ChildGraph.Factory

@GraphExtension
interface ChildGraph : GrandchildGraph.Factory {
  val childValue: String

  @GraphExtension.Factory
  interface Factory {
    fun createChild(@Provides value: String = "real"): ChildGraph
  }
}

@GraphExtension
interface GrandchildGraph {
  val grandchildValue: String

  @GraphExtension.Factory
  interface Factory {
    fun createGrandchild(@Provides value: String = "real"): GrandchildGraph
  }
}

@BindingContainer
object FakeBindings {
  @Provides val value: String = "fake"
}

fun box(): String {
  val root = createDynamicGraph<RootGraph>(FakeBindings)

  // Dynamic binding should propagate to child
  val child = root.createChild()
  assertEquals("fake", child.childValue)

  // Dynamic binding should propagate to grandchild
  val grandchild = child.createGrandchild()
  assertEquals("fake", grandchild.grandchildValue)

  return "OK"
}
