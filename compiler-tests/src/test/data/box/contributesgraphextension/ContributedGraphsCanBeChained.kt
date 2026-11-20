package test

abstract class LoggedInScope
abstract class ProfileScope

@GraphExtension(ProfileScope::class)
interface ProfileGraph {
  val string: String

  @GraphExtension.Factory @ContributesTo(LoggedInScope::class)
  interface Factory {
    fun createProfileGraph(): ProfileGraph
  }
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int

  @Provides fun provideString(int: Int): String = int.toString()

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  val exampleGraph = createGraph<ExampleGraph>()
  val loggedInGraph = exampleGraph.createLoggedInGraph()
  assertEquals(0, loggedInGraph.int)
  val profileGraph = (loggedInGraph as ProfileGraph.Factory).createProfileGraph()
  assertEquals("0", profileGraph.string)
  return "OK"
}
