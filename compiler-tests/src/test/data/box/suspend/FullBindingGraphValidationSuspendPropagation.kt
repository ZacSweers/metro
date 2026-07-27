// ENABLE_SUSPEND_PROVIDERS
// ENABLE_FULL_BINDING_GRAPH_VALIDATION

var configInitializations = 0

abstract class ChildScope private constructor()

class Config(val value: String)

class Repository(val config: Config)

@GraphExtension(ChildScope::class)
interface ChildGraph {
  suspend fun repository(): Repository

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createChild(): ChildGraph
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides
  suspend fun provideConfig(): Config {
    configInitializations++
    return Config("config")
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideRepository(config: Config): Repository = Repository(config)
}

fun box(): String {
  val child = createGraph<AppGraph>().createChild()
  return runBlocking {
    val first = child.repository()
    val second = child.repository()
    assertEquals("config", first.config.value)
    assertTrue(first === second)
    assertEquals(1, configInitializations)
    "OK"
  }
}
