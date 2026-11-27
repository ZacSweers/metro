// Two extensions request a parent-scoped class by different keys. The parent holds one instance.

@Qualifier annotation class Chosen

@Chosen @SingleIn(AppScope::class) @Inject class Engine

@DependencyGraph(AppScope::class)
interface AppGraph {
  val unqualifiedChild: UnqualifiedChild

  val qualifiedChild: QualifiedChild

  val bothChild: BothChild
}

@GraphExtension
interface UnqualifiedChild {
  val engine: Engine
}

@GraphExtension
interface QualifiedChild {
  @Chosen val engine: Engine
}

@GraphExtension
interface BothChild {
  @Chosen val chosenEngine: Engine

  val engine: Engine
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val engine = graph.unqualifiedChild.engine
  assertSame(engine, graph.qualifiedChild.engine)
  assertSame(engine, graph.bothChild.engine)
  assertSame(engine, graph.bothChild.chosenEngine)
  return "OK"
}
