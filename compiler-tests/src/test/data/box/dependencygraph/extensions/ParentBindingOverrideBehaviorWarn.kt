// PARENT_BINDING_OVERRIDE_BEHAVIOR: WARN
// Under WARN, a graph extension may shadow an inherited binding without `@OverridesParentBinding`.
// The compiler emits a warning (not an error) and the child binding still wins.

abstract class ChildScope private constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
  val message: String
  val childGraph: ChildGraph

  @Provides fun appMessage(): String = "parent"
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val message: String

  @Provides fun childMessage(): String = "child"
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals("parent", appGraph.message)
  assertEquals("child", appGraph.childGraph.message)
  return "OK"
}
