// Regression test for https://github.com/ZacSweers/metro/issues/377#issuecomment-2878782694

sealed interface LoggedInScope

@Inject @SingleIn(AppScope::class) class Dependency
@Inject @SingleIn(LoggedInScope::class) class ChildDependency(val dep: Dependency)

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val dependency: Dependency
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
    fun inject(screen: LoggedInScreen)

    @GraphExtension.Factory @ContributesTo(AppScope::class)
    interface Factory {
        fun createLoggedInGraph(): LoggedInGraph
    }
}

class LoggedInScreen {
    @Inject lateinit var childDependency: ChildDependency
}

fun box(): String {
  val child = createGraph<ExampleGraph>().createLoggedInGraph()
  val first = LoggedInScreen().also(child::inject)
  val second = LoggedInScreen().also(child::inject)
  assertSame(first.childDependency, second.childDependency)
  return "OK"
}
