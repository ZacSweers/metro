// Verify that a child graph extension can use a scoped dependency from the parent
// that is otherwise unused and unreferenced in the parent graph itself.
// This relies on "scoped inject class hints" added in PR 883.

@Scope
annotation class AppScope

@Scope
annotation class ChildScope

@SingleIn(AppScope::class)
class ScopedInParent @Inject constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childFactory: ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val scopedInParent: ScopedInParent

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val child1 = appGraph.childFactory.create()
  val child2 = appGraph.childFactory.create()

  val instance1 = child1.scopedInParent
  val instance2 = child2.scopedInParent

  if (instance1 !== instance2) return "Fail: instances are not the same"
  return "OK"
}
