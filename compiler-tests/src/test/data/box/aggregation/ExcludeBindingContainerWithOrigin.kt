@Origin(ContainerTrigger::class)
@ContributesTo(AppScope::class)
@BindingContainer
class OriginatedBindingContainer {
  @Provides fun bindDependency(): Dependency = Dependency("Override")
}

class ContainerTrigger

@Inject
class Dependency(val value: String = "default")

// Excluding ContainerTrigger should result in falling back to the regular Dependency use
@DependencyGraph(AppScope::class, excludes = [ContainerTrigger::class])
interface AppGraph {
  val dependency: Dependency
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("default", graph.dependency.value)
  return "OK"
}
