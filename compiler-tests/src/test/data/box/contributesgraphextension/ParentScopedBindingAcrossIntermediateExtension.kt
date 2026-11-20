// A nested child can request a constructor binding in the root scope without an explicit root accessor.

sealed interface IntermediateScope
sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency
@Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  // Works if added explicitly like this
  // val dependency: Dependency
}

@GraphExtension(IntermediateScope::class)
interface IntermediateGraph {
    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createIntermediateGraph(): IntermediateGraph
    }
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
    val childDependency: ChildDependency

    @GraphExtension.Factory @ContributesTo(IntermediateScope::class)
    interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
    }
}

fun box(): String {
  val intermediate = createGraph<ExampleGraph>().createIntermediateGraph()
  val child = (intermediate as LoggedInGraph.Factory).createLoggedInGraph()
  val first = child.childDependency
  val second = child.childDependency
  assertSame(first, second)
  assertSame(first.dep, second.dep)
  return "OK"
}
