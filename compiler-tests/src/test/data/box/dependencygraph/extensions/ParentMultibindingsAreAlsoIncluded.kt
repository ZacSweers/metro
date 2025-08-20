// https://github.com/ZacSweers/metro/issues/835
@DependencyGraph(bindingContainers = [IntBindings::class])
interface AppGraph {
  @Multibinds(allowEmpty = true) val strings: Set<String>

  fun childGraphFactory(): ChildGraph.Factory
}

@BindingContainer
interface IntBindings {
  @Multibinds(allowEmpty = true) val ints: Set<Int>
}

@GraphExtension
interface ChildGraph {
  val ints: Set<Int>
  val strings: Set<String>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childGraphFactory().create()
  assertTrue(child.ints.isEmpty())
  assertTrue(child.strings.isEmpty())
  return "OK"
}