// https://github.com/ZacSweers/metro/issues/1331
// Namely this ensures that a parent-unused, unscoped binding referenced by a child graph still gets
// a property collected

@DependencyGraph(AppScope::class)
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}

interface LoggedInScope

@SingleIn(LoggedInScope::class)
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val loggedInFeatureInteractor: FeatureInteractor

  @Provides
  @SingleIn(LoggedInScope::class)
  fun provideLoggedInFeatureInteractor(factory: FeatureGraph.Factory): FeatureInteractor =
    object : FeatureInteractor {}.also { factory.createFeatureGraph() }
}

interface FeatureScope

@GraphExtension(FeatureScope::class)
interface FeatureGraph {
  @GraphExtension.Factory
  interface Factory {
    fun createFeatureGraph(): FeatureGraph
  }

  @ContributesTo(AppScope::class)
  interface ParentBindings {
    fun featureFactory(): Factory
  }
}

interface FeatureInteractor

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertNotNull(appGraph)
  return "OK"
}
