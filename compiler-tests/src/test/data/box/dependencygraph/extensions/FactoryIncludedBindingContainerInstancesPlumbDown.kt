//https://github.com/ZacSweers/metro/issues/993
@DependencyGraph
interface AppGraph {
  fun childGraphFactory(): ChildGraph.Factory

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes bindings: Bindings): AppGraph
  }
}

@BindingContainer
class Bindings {
  @Provides
  fun provideInt(): Int = 3
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
  val parent = createGraphFactory<AppGraph.Factory>().create(Bindings())
  val child = parent.childGraphFactory().create()
  assertEquals(3, child.int)
  return "OK"
}
