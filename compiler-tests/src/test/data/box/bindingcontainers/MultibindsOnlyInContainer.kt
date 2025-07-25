@BindingContainer
interface MyMultibinds {
  @Multibinds(allowEmpty = true)
  val ints: Set<Int>
}

@DependencyGraph(bindingContainers = [MyMultibinds::class])
interface AppGraph {
  val ints: Set<Int>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(emptySet(), graph.ints)
  return "OK"
}