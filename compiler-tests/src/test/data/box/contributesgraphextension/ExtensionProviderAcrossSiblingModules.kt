// MODULE: scope
// FILE: Source1.kt

abstract class LoggedInScope

// MODULE: extension(scope)
// FILE: Source2.kt

@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val string: String

  @GraphExtension.Factory @ContributesTo(AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

// MODULE: contribution(scope)
// FILE: Source3.kt

@ContributesTo(LoggedInScope::class)
interface LoggedInStringProvider {
  @Provides fun provideString(int: Int): String = int.toString()
}

// MODULE: main(scope, extension, contribution)
// FILE: Source4.kt

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @Provides fun provideInt(): Int = 0
}

fun box(): String {
  val child = createGraph<ExampleGraph>().createLoggedInGraph()
  assertEquals("0", child.string)
  return "OK"
}
