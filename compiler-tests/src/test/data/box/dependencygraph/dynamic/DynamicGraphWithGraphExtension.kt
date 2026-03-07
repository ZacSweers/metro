// https://github.com/ZacSweers/metro/issues/1543

@DependencyGraph interface RootGraph : SubGraph.Factory

@GraphExtension
interface SubGraph {
  val value: String

  @GraphExtension.Factory
  interface Factory {
    fun createSubGraph(@Provides value: String = "real"): SubGraph
  }
}

@BindingContainer
object FakeBindings {
  @Provides val value: String = "fake"
}

fun box(): String {
  val subGraph = createDynamicGraph<RootGraph>(FakeBindings).createSubGraph()

  assertEquals("fake", subGraph.value)
  return "OK"
}
