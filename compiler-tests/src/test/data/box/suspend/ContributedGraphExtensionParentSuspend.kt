// ENABLE_SUSPEND_PROVIDERS

// A contributed graph extension (its factory is @ContributesTo the parent scope) whose child
// consumes a suspend binding owned by the parent graph.

abstract class ChildScope private constructor()

@Inject class Repository(val database: String)

@GraphExtension(ChildScope::class)
interface ChildGraph {
  suspend fun repository(): Repository

  suspend fun database(): String

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun createChild(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides suspend fun provideDatabase(): String = "db"
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.createChild()
  return runBlocking {
    assertEquals("db", child.repository().database)
    assertEquals("db", child.database())
    "OK"
  }
}
