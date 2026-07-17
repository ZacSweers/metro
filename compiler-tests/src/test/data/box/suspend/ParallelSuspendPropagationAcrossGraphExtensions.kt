// ENABLE_SUSPEND_PROVIDERS
// PARALLEL_THREADS: 4

var configInitializations = 0

abstract class FeatureAScope private constructor()

abstract class FeatureBScope private constructor()

abstract class LeafOneScope private constructor()

abstract class LeafTwoScope private constructor()

class Config(val value: String)

class Repository(val config: Config)

@Inject class DeferredConsumer(val repository: suspend () -> Repository)

@GraphExtension(LeafOneScope::class)
interface LeafOneGraph {
  suspend fun repository(): Repository

  @GraphExtension.Factory
  @ContributesTo(FeatureAScope::class)
  fun interface Factory {
    fun createLeafOne(): LeafOneGraph
  }
}

@GraphExtension(LeafTwoScope::class)
interface LeafTwoGraph {
  suspend fun repository(): Repository
  val deferredConsumer: DeferredConsumer

  @GraphExtension.Factory
  @ContributesTo(FeatureAScope::class)
  fun interface Factory {
    fun createLeafTwo(): LeafTwoGraph
  }
}

@GraphExtension(FeatureAScope::class)
interface FeatureAGraph {
  suspend fun repository(): Repository
  val leafOneFactory: LeafOneGraph.Factory
  val leafTwoFactory: LeafTwoGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeatureA(): FeatureAGraph
  }
}

@GraphExtension(FeatureBScope::class)
interface FeatureBGraph {
  suspend fun repository(): Repository

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeatureB(): FeatureBGraph
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
  val app = createGraph<AppGraph>()
  val featureA = app.createFeatureA()
  val featureB = app.createFeatureB()
  val leafOne = featureA.leafOneFactory.createLeafOne()
  val leafTwo = featureA.leafTwoFactory.createLeafTwo()
  val deferredConsumer = leafTwo.deferredConsumer

  assertEquals(0, configInitializations)

  return runBlocking {
    val repositories =
      listOf(
        featureA.repository(),
        featureB.repository(),
        leafOne.repository(),
        leafTwo.repository(),
        deferredConsumer.repository(),
      )
    assertEquals("config", repositories.first().config.value)
    assertTrue(repositories.all { it === repositories.first() })
    assertEquals(1, configInitializations)
    "OK"
  }
}
