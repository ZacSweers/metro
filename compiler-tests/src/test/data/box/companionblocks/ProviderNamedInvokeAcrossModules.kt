// MIN_COMPILER_VERSION: 2.5.0-dev-7307
// GENERATE_CLASSES_IN_IR: true
// OMIT_REDUNDANT_MIRRORS: true

// Downstream lookups of the `invoke` helpers skip each factory's `invoke()` override.

// MODULE: lib
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Library.kt
class Dep(val value: String)

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

// MODULE: main(lib)
// COMPANION_MODE: COMPANION_BLOCK
// FILE: Main.kt
@DependencyGraph(bindingContainers = [ObjectProviders::class, ContainerProviders::class])
interface AppGraph {
  val objectValue: ObjectValue
  val containerValue: ContainerValue

  @Provides fun dep(): Dep = Dep("dep")
}

@DependencyGraph(bindingContainers = [ObjectProviders::class, ContainerProviders::class])
interface ProviderGraph {
  val objectValueProvider: Provider<ObjectValue>
  val containerValueProvider: Provider<ContainerValue>

  @Provides fun dep(): Dep = Dep("provider")
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("dep", graph.objectValue.dep.value)
  assertEquals("dep", graph.containerValue.dep.value)
  val providerGraph = createGraph<ProviderGraph>()
  assertEquals("provider", providerGraph.objectValueProvider().dep.value)
  assertEquals("provider", providerGraph.containerValueProvider().dep.value)
  return "OK"
}
