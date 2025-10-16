@Origin(ContainerTrigger::class)
@ContributesTo(Unit::class)
@BindingContainer
class OriginatedBindingContainer {
  @Provides fun bindDependency(): Dependency = Dependency("Override")
}

class ContainerTrigger

@Inject
class Dependency(val value: String = "default")

@GraphExtension(Unit::class, excludes = [ContainerTrigger::class])
interface UnitGraph {
  val dependency: Dependency
}

// Excluding ContainerTrigger should result in falling back to the regular Dependency use
@DependencyGraph(AppScope::class)
interface AppGraph {
  val unitGraph: UnitGraph
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val unitGraph = graph.unitGraph
  assertEquals("default", graph.unitGraph.dependency.value)
  return "OK"
}
