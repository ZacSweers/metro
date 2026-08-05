// ENABLE_SUSPEND_PROVIDERS

// With suspend providers enabled, Metro checks every requested binding to determine whether its
// dependency chain contains a suspend binding. ChildScopedThing belongs to the middle graph but is
// only requested by the innermost graph.

sealed interface ChildScope

sealed interface ChildInnerScope

@SingleIn(ChildScope::class)
@Inject
class ChildScopedThing

@GraphExtension(ChildInnerScope::class)
interface ChildInnerGraph {
  fun childScopedThing(): ChildScopedThing

  @GraphExtension.Factory
  @ContributesTo(ChildScope::class)
  interface Factory {
    fun createChildInnerGraph(): ChildInnerGraph
  }
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  fun childInnerGraphFactory(): ChildInnerGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChildGraph(): ChildGraph
  }
}

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val firstChild = appGraph.createChildGraph()
  val secondChild = appGraph.createChildGraph()

  val first = firstChild.childInnerGraphFactory().createChildInnerGraph().childScopedThing()
  val sibling = firstChild.childInnerGraphFactory().createChildInnerGraph().childScopedThing()
  val otherChild = secondChild.childInnerGraphFactory().createChildInnerGraph().childScopedThing()

  assertSame(first, sibling)
  assertNotSame(first, otherChild)
  return "OK"
}
