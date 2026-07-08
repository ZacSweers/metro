@DependencyGraph(AppScope::class)
interface AppGraph

interface Foo

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class FooImpl: Foo

@DependencyGraph(Unit::class)
interface FeatureGraph {
  val foo: Lazy<Foo>

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes component: FeatureComponent): FeatureGraph
  }
}

@ContributesTo(AppScope::class)
interface FeatureComponent {
  val foo: Lazy<Foo>
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val featureGraph = createGraphFactory<FeatureGraph.Factory>().create(appGraph)
  assertNotNull(featureGraph.foo.value)
  return "OK"
}