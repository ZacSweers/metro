// MODULE: lib
interface FeatureScope

@DependencyGraph(FeatureScope::class, isExtendable = true)
interface FeatureGraph {

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Includes serviceProvider: FeatureServiceProvider
    ): FeatureGraph
  }
}

@ContributesTo(AppScope::class)
interface FeatureServiceProvider {
  val int: Int
}

@ContributesGraphExtension(scope = Unit::class)
interface LoggedInGraph {
  val int: Int

  @ContributesGraphExtension.Factory(FeatureScope::class)
  interface Factory {
    fun createLoggedInGraph(
      @Provides str: String
    ): LoggedInGraph
  }
}

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides
  fun provideInt(): Int = 3
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val feature = createGraphFactory<FeatureGraph.Factory>()
    .create(parent.asContribution<FeatureServiceProvider>())
  val child = feature.createLoggedInGraph("str")
  assertEquals(child.int, 3)
  return "OK"
}