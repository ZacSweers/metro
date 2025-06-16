interface FeatureScope

interface IntProvider {
  val int: Int
}

@DependencyGraph(scope = FeatureScope::class, isExtendable = true)
interface FeatureGraph {

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Includes serviceProvider: FeatureServiceProvider,
    ): FeatureGraph
  }
}

@ContributesGraphExtension(scope = Unit::class)
interface FeatureSubGraph {

  @ContributesGraphExtension.Factory(scope = FeatureScope::class)
  interface Factory {
    fun create(): FeatureSubGraph
  }
}

@ContributesTo(AppScope::class)
interface FeatureServiceProvider {
  fun intProvider(): IntProvider
}

@DependencyGraph(AppScope::class)
interface AppGraph

@Inject
@ContributesBinding(AppScope::class)
class IntProviderImpl : IntProvider {
  override val int: Int get() = 3
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val feature = createGraphFactory<FeatureGraph.Factory>()
    .create(parent.asContribution<FeatureServiceProvider>())
  val child = feature.create()
  assertNotNull(child)
  return "OK"
}