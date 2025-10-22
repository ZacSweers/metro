// MODULE: lib
package otherpackage

@ContributesBinding(AppScope::class)
@Inject
class Dependency

// MODULE: main(lib)
package thispackage

@ContributesBinding(AppScope::class)
@Inject
class Dependency


@DependencyGraph(AppScope::class)
interface AppGraph {
    val thisDependency: thispackage.Dependency
    val otherDependency: otherpackage.Dependency
}


fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.thisDependency is thispackage.Dependency)
  assertTrue(graph.otherDependency is otherpackage.Dependency)
  return "OK"
}
