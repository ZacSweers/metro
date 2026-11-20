// Regression test for https://github.com/ZacSweers/metro/pull/351

abstract class LoggedInScope

interface GraphExtensionFactory<T> {
  fun createGraph(): T
}

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val int: Int

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory : GraphExtensionFactory<LoggedInGraph>
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  assertEquals(0, createGraph<ExampleGraph>().createGraph().int)
  return "OK"
}
