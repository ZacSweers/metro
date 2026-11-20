// https://github.com/ZacSweers/metro/issues/377

// A child request for a parent-scoped constructor binding adds it to the parent.

sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency
@Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  // Works if added explicitly like this
  // val dependency: Dependency
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
    val childDependency: ChildDependency

    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
    }
}

fun box(): String {
  val child = createGraph<ExampleGraph>().createLoggedInGraph()
  val first = child.childDependency
  val second = child.childDependency
  assertSame(first, second)
  assertSame(first.dep, second.dep)
  return "OK"
}
