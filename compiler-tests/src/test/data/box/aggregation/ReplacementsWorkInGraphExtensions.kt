// Regression test for https://github.com/ZacSweers/metro/issues/1197
interface Dependency

@ContributesBinding(Unit::class)
@SingleIn(Unit::class)
@Inject
class DefaultDependency : Dependency

@ContributesBinding(Unit::class, replaces = [DefaultDependency::class])
@SingleIn(Unit::class)
@Inject
class RealDependency : Dependency

@DependencyGraph(AppScope::class)
interface AppGraph

@GraphExtension(Unit::class)
interface LoggedInGraph {

  val dependency: Dependency

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val loggedInGraph = appGraph.createLoggedInGraph()
  assertEquals("RealDependency", loggedInGraph.dependency::class.qualifiedName)
  return "OK"
}