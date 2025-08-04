// https://github.com/ZacSweers/metro/issues/835
@DependencyGraph(bindingContainers = [IntBindings::class], isExtendable = true)
interface AppGraph {
  @Multibinds(allowEmpty = true) val strings: Set<String>
}

@BindingContainer
interface IntBindings {
  @Multibinds(allowEmpty = true) val ints: Set<Int>
}

@DependencyGraph
interface ChildGraph {
  val ints: Set<Int>
  val strings: Set<String>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Extends parent: AppGraph): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = createGraphFactory<ChildGraph.Factory>().create(parent)
  assertTrue(child.ints.isEmpty())
  assertTrue(child.strings.isEmpty())
  return "OK"
}