// https://github.com/ZacSweers/metro/issues/694
// The syntax goes name(deps)(friends)(dependsOn)

// MODULE: lib
interface ContributedInterface

@ContributesBinding(Unit::class)
@Inject
internal class Impl : ContributedInterface

@GraphExtension(Unit::class)
internal interface UnitGraph {
  val contributed: ContributedInterface

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createUnitGraph(): UnitGraph
  }
}

// MODULE: main()(lib)
@DependencyGraph(AppScope::class)
internal interface AppGraph

fun box(): String {
  val graph = createGraph<AppGraph>()
  val unitGraph = graph.createUnitGraph()
  assertNotNull(unitGraph.contributed)
  return "OK"
}