package test

interface MultiboundType
abstract class LoggedInScope

@Inject
class MultiImpl : MultiboundType

@ContributesTo(AppScope::class)
interface MultibindingsModule2 {
  // Important for @Multibinds to be used for this test's coverage, as opposed to @ElementsIntoSet
  @Multibinds(allowEmpty = true)
  fun provideMulti(): Set<MultiboundType>
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val multi: Set<MultiboundType>

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(AppScope::class)
interface ExampleGraph

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val loggedInGraph = graph.createLoggedInGraph()
  assertNotNull(loggedInGraph.multi)
  return "OK"
}
