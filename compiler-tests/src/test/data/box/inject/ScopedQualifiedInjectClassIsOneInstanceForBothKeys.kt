// GENERATE_CONTRIBUTION_PROVIDERS: false

// A scoped class has one instance in its graph, whichever key requests it.

@Qualifier annotation class Chosen

@Chosen @SingleIn(AppScope::class) @Inject class Engine

interface Repo

// The generated @Binds requests RealRepo with no qualifier.
@Chosen @SingleIn(AppScope::class) @Inject @ContributesBinding(AppScope::class) class RealRepo : Repo

@DependencyGraph(AppScope::class)
interface AppGraph {
  val engine: Engine

  @Chosen val chosenEngine: Engine

  @Chosen val repo: Repo

  @Chosen val realRepo: RealRepo
}

@Chosen @SingleIn(AppScope::class) @Inject class Wheel

@DependencyGraph(AppScope::class)
interface DeclaredGraph {
  // A parameter-less @Binds declares the class with no qualifier.
  @Binds fun bindWheel(): Wheel

  val wheel: Wheel

  @Chosen val chosenWheel: Wheel
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertSame(graph.engine, graph.chosenEngine)
  assertSame(graph.engine, graph.engine)
  assertSame<Any>(graph.repo, graph.realRepo)

  val declaredGraph = createGraph<DeclaredGraph>()
  assertSame(declaredGraph.wheel, declaredGraph.chosenWheel)
  return "OK"
}
