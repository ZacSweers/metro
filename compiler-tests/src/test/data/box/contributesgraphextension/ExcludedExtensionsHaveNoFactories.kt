// CHECK_BYTECODE_TEXT

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class, excludes = [LoggedInGraph.Factory::class])
interface ExcludeFactory

@DependencyGraph(AppScope::class, excludes = [LoggedInGraph::class])
interface ExcludeGraph

fun box(): String {
  assertFalse(createGraph<ExcludeFactory>() is LoggedInGraph.Factory)
  assertFalse(createGraph<ExcludeGraph>() is LoggedInGraph.Factory)
  return "OK"
}

// Excluding either declaration also removes its generated contribution.
// 0 class ExcludeFactory\$+ContributedLoggedInGraph
// 0 class ExcludeGraph\$+ContributedLoggedInGraph
