@DependencyGraph(bindingContainers = [Bindings::class], isExtendable = true) interface AppGraph

@BindingContainer
class Bindings {
  @Provides fun provideInt(): Int = 3
}

@DependencyGraph
interface ChildGraph {
  val int: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Extends parent: AppGraph): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = createGraphFactory<ChildGraph.Factory>().create(parent)
  assertEquals(3, child.int)
  return "OK"
}
