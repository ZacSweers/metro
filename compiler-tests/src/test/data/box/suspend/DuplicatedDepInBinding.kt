// ENABLE_SUSPEND_PROVIDERS

// MODULE: api
interface Foo
interface Bar

@BindingContainer
object BarProvider {
  @Provides
  fun provideBar(): Bar = object : Bar {}
}

// MODULE: impl(api)
@Inject
@ContributesBinding(Unit::class)
class FooImpl(
  private val bar: Bar,
  duplicatedBar: Bar,
) : Foo

// MODULE: main(api, impl)
@DependencyGraph(AppScope::class)
interface AppGraph

@GraphExtension(Unit::class, bindingContainers = [BarProvider::class])
interface FeatureGraph {

  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  interface Factory {
    fun createFeatureGraph(): FeatureGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val featureGraph = appGraph.createFeatureGraph()
  assertNotNull(featureGraph)

  return "OK"
}
