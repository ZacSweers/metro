// Regression test for https://github.com/ZacSweers/metro/issues/531
// MODULE: lib
@GraphExtension(Unit::class)
interface LoggedInGraph {
  fun inject(member: LoggedInScreen)

  @GraphExtension.Factory @ContributesTo(scope = AppScope::class)
  interface Factory {
    fun createLoggedInGraph(): LoggedInGraph
  }
}

class LoggedInScreen {
  @Inject @Named("string")
  lateinit var string: String
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes stringProvider: StringProvider): AppGraph
  }
}

interface StringProvider {
  @Named("string")
  val string: String
}

fun box(): String {
  val stringProvider = object : StringProvider {
    override val string: String = "Hello, world!"
  }
  val appGraph = createGraphFactory<AppGraph.Factory>().create(stringProvider)
  val loggedInGraph = appGraph.createLoggedInGraph()
  val screen = LoggedInScreen()
  loggedInGraph.inject(screen)
  assertEquals(stringProvider.string, screen.string)
  return "OK"
}