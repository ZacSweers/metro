// Two unrelated graphs in one compilation request the same qualified @Inject class. One uses its
// qualifier and the other uses none.

@Qualifier annotation class Chosen

@Chosen @Inject class Engine

@DependencyGraph
interface UnqualifiedGraph {
  val engine: Engine
}

@DependencyGraph
interface QualifiedGraph {
  @Chosen val engine: Engine
}

fun box(): String {
  assertNotNull(createGraph<UnqualifiedGraph>().engine)
  assertNotNull(createGraph<QualifiedGraph>().engine)
  return "OK"
}
