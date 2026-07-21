// ENABLE_SUSPEND_PROVIDERS

// The documented remedy for member injection over suspend bindings: declare the member as
// `suspend () -> T`. The wrapper defers initialization, so the injector stays non-suspend.

class Target {
  @Inject lateinit var database: suspend () -> String
  @Inject lateinit var lazyDatabase: SuspendLazy<String>
}

@DependencyGraph
interface ExampleGraph {
  fun inject(target: Target)

  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val target = Target()
  graph.inject(target)
  return runBlocking {
    assertEquals("db", target.database())
    assertEquals("db", target.lazyDatabase.value())
    "OK"
  }
}
