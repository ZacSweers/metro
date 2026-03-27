// Repro for a bug where using @Includes on an injected class is fine, but not injected data class.
@Inject
data class ExternalDependencies(
  val int: Int,
)

@DependencyGraph
interface AppGraph {
  val dependencies: ExternalDependencies

  @Provides
  fun provideInt(): Int = 3
}

@DependencyGraph
interface FeatureGraph {
  val int: Int

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(
      @Includes dependencies: ExternalDependencies
    ): FeatureGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val featureGraph = createGraphFactory<FeatureGraph.Factory>().create(appGraph.dependencies)
  assertEquals(3, featureGraph.int)
  return "OK"
}
