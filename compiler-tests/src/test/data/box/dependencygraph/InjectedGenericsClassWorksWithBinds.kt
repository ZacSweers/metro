interface Repo<T>

@Inject
class ExampleClass(
  intRepo: Repo<Int>,
  doubleRepo: Repo<Double>
)

@Inject
class RepoImpl<T> : Repo<T>

@ContributesTo(AppScope::class)
interface RepoProviders {
  @Binds
  fun RepoImpl<Int>.bindIntRepo(): Repo<Int>

  @Binds
  fun RepoImpl<Double>.bindDoubleRepo(): Repo<Double>
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val example: ExampleClass
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.example)
  return "OK"
}