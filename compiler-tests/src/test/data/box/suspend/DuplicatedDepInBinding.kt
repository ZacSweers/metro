// ENABLE_SUSPEND_PROVIDERS

// MODULE: lib
interface Foo

@Inject
@ContributesBinding(Unit::class)
class FooImpl(
  private val int: Int,
  duplicatedInt: Int,
) : Foo

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val featureGraph: FeatureGraph
}

@GraphExtension(Unit::class)
interface FeatureGraph {
  val viewModel: ViewModel

  @Provides
  fun provideInt(): Int = 3
}

interface ViewModel

@SingleIn(Unit::class)
@Inject
@ContributesBinding(Unit::class)
class ViewModelImpl(foo: Foo): ViewModel

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  assertNotNull(appGraph.featureGraph.viewModel)

  return "OK"
}
