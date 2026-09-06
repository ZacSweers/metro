@DependencyGraph(bindingContainers = [StringBindings::class])
interface AppGraph {
  val string: String
}

// Simple self-referencing container
@BindingContainer(includes = [StringBindings::class])
class StringBindings {
  @Provides
  fun provideString(): String {
    return "string value"
  }
}

interface CycleValues {
  val label: String
  val count: Int
  val extra: Long
}

@BindingContainer
class ExtraBindings {
  @Provides fun extra(): Long = 3L
}

@BindingContainer(includes = [ForwardB::class, ExtraBindings::class])
class ForwardA {
  @Provides fun label(): String = "forward"
}

@BindingContainer(includes = [ForwardA::class])
class ForwardB {
  @Provides fun count(): Int = 1
}

@DependencyGraph(bindingContainers = [ForwardA::class])
interface ForwardFirstGraph : CycleValues

@DependencyGraph(bindingContainers = [ForwardB::class])
interface ForwardSecondGraph : CycleValues

@BindingContainer(includes = [ReverseB::class, ExtraBindings::class])
class ReverseA {
  @Provides fun label(): String = "reverse"
}

@BindingContainer(includes = [ReverseA::class])
class ReverseB {
  @Provides fun count(): Int = 2
}

// Resolve a second cycle from its other end before asking for the intermediate container's closure.
@DependencyGraph(bindingContainers = [ReverseB::class])
interface ReverseFirstGraph : CycleValues

@DependencyGraph(bindingContainers = [ReverseA::class])
interface ReverseSecondGraph : CycleValues

fun checkCycle(graph: CycleValues, label: String, count: Int) {
  assertEquals(label, graph.label)
  assertEquals(count, graph.count)
  assertEquals(3L, graph.extra)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("string value", graph.string)
  checkCycle(createGraph<ForwardFirstGraph>(), "forward", 1)
  checkCycle(createGraph<ForwardSecondGraph>(), "forward", 1)
  checkCycle(createGraph<ReverseFirstGraph>(), "reverse", 2)
  checkCycle(createGraph<ReverseSecondGraph>(), "reverse", 2)
  return "OK"
}
