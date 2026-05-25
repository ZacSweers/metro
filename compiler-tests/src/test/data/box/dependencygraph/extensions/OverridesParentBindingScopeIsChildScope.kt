// PARENT_BINDING_OVERRIDE_BEHAVIOR: REQUIRE_ANNOTATION
// Verifies that when a graph extension's @OverridesParentBinding replaces a scoped parent
// binding, the child binding's own scope is what's used in the child's view. The parent's view
// continues to use its own scoped instance. Runs under REQUIRE_ANNOTATION so the annotation is
// what permits the override.

abstract class ChildScope private constructor()

class Logger(val origin: String)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val logger: Logger
  val childGraph: ChildGraph

  @SingleIn(AppScope::class) @Provides fun appLogger(): Logger = Logger("app")
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val logger: Logger

  @SingleIn(ChildScope::class)
  @Provides
  @OverridesParentBinding
  fun childLogger(): Logger = Logger("child")
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  // Parent sees its own AppScope-scoped Logger.
  assertEquals("app", appGraph.logger.origin)
  val a = appGraph.logger
  assertSame(a, appGraph.logger) // Same instance — scoped in AppScope

  val childGraph = appGraph.childGraph
  // Child sees its own ChildScope-scoped Logger.
  assertEquals("child", childGraph.logger.origin)
  val c = childGraph.logger
  assertSame(c, childGraph.logger) // Same instance — scoped in ChildScope
  return "OK"
}
