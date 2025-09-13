@DependencyGraph
interface ExampleGraph {

  @DependencyGraph.Factory
  interface Factory {
      fun create(@Includes serviceProvider: ServiceProvider): ExampleGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val int: Int

  @Provides
  fun providesInt(): Int = 3
}

@ContributesTo(AppScope::class)
interface ServiceProvider {
  val int: Int
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertEquals(appGraph.int, 3)
  return "OK"
}