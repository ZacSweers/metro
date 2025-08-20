@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  fun childGraphFactory(): ChildGraph.Factory
}

@BindingContainer
class Bindings {
  @Provides fun provideInt(): Int = 3
}

@GraphExtension
interface ChildGraph {
  val int: Int

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childGraphFactory().create()
  assertEquals(3, child.int)
  return "OK"
}
