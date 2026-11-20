package test

abstract class LoggedInScope

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val string: String

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(@Provides @Named("long") long: Long): LoggedInGraph
  }
}

@ContributesTo(LoggedInScope::class)
interface LoggedInStringProvider {
  @Provides
  fun provideString(int: Int, @Named("long") long: Long): String = (int + long).toString()
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val loggedInGraph = exampleGraph.createLoggedInGraph(2L)
  assertEquals("2", loggedInGraph.string)
  return "OK"
}
