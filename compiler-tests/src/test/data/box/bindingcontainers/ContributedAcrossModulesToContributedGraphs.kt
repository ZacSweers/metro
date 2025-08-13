// MODULE: lib
@ContributesTo(Unit::class)
@BindingContainer
object SampleDependency {
  @Provides fun provideInt(): Int = 3
}

@ContributesGraphExtension(Unit::class)
interface LoggedInGraph {
  val int: Int

  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph

fun box(): String {
  val loggedInGraph = createGraph<AppGraph>().createLoggedInGraph()
  assertEquals(3, loggedInGraph.int)
  return "OK"
}