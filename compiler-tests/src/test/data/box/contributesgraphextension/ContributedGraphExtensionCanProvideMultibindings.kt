package test

object AppScope
object LoggedInScope

@DependencyGraph(AppScope::class)
interface ExampleGraph

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val ints: Set<Int>
  @Provides @IntoSet fun provideInt1(): Int = 1
  @Provides @IntoSet fun provideInt2(): Int = 2
  @Provides
  @ElementsIntoSet
  fun provideInts(): Set<Int> = setOf(3, 4)

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory1 {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val loggedInGraph = graph.createLoggedInGraph()
  val ints = loggedInGraph.ints
  assertNotNull(ints)
  assertEquals(setOf(1, 2, 3, 4), ints)
  return "OK"
}
