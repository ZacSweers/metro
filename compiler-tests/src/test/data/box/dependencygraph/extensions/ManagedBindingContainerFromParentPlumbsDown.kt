// Managed binding container instances from a parent graph should be propagated
// to child graph extensions, making their bindings available even when the
// parent graph doesn't directly expose those bindings as accessors.
// https://github.com/ZacSweers/metro/pull/883

@BindingContainer
class ParentBindings {
  @Provides fun provideString(): String = "from parent binding container"
}

@DependencyGraph(bindingContainers = [ParentBindings::class])
interface AppGraph {
  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension
interface ChildGraph {
  val string: String

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childGraphFactory().create()
  assertEquals("from parent binding container", child.string)
  return "OK"
}
