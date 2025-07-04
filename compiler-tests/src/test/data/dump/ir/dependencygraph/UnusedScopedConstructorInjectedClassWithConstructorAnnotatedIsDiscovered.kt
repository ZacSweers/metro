// Repro https://github.com/ZacSweers/metro/issues/664
// IR dump test to ensure we look up Dependency
sealed interface LoggedInScope

@SingleIn(AppScope::class) class Dependency @Inject constructor()
@Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

@DependencyGraph(scope = AppScope::class, isExtendable = true)
interface ExampleGraph

@ContributesGraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val childDependency: ChildDependency

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}