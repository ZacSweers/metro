// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// COMPANION_MODE: COMPANION_BLOCK
// GENERATE_CLASSES_IN_IR: true

// Each provider factory's `invoke()` override shares a name with the helper for its `invoke`
// provider. Private providers make the graph call that helper.
class Dep(val value: String)

class GraphValue(val dep: Dep)

class ObjectValue(val dep: Dep)

class ContainerValue(val dep: Dep)

@BindingContainer
object ObjectProviders {
  @Provides private fun invoke(dep: Dep): ObjectValue = ObjectValue(dep)
}

@BindingContainer
interface ContainerProviders {
  companion {
    @Provides private fun invoke(dep: Dep): ContainerValue = ContainerValue(dep)
  }
}

@DependencyGraph(bindingContainers = [ObjectProviders::class, ContainerProviders::class])
interface AppGraph {
  val graphValue: GraphValue
  val objectValue: ObjectValue
  val containerValue: ContainerValue

  @Provides fun dep(): Dep = Dep("dep")

  @Provides private fun invoke(dep: Dep): GraphValue = GraphValue(dep)
}

@DependencyGraph(bindingContainers = [ContainerProviders::class])
interface ProviderGraph {
  val containerValueProvider: Provider<ContainerValue>

  @Provides fun dep(): Dep = Dep("provider")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("dep", graph.graphValue.dep.value)
  assertEquals("dep", graph.objectValue.dep.value)
  assertEquals("dep", graph.containerValue.dep.value)
  assertEquals("provider", createGraph<ProviderGraph>().containerValueProvider().dep.value)
  return "OK"
}
