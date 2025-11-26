@DependencyGraph(AppScope::class)
interface AppGraph

@GraphExtension(bindingContainers = [IncludedContainer::class])
interface ActivityGraph {
  @ContributesTo(AppScope::class)
  @GraphExtension.Factory
  fun interface Factory {
    fun create(): ActivityGraph
  }
}

@ContributesTo(AppScope::class)
@BindingContainer(includes = [IncludedContainer::class])
object AppContainer

@BindingContainer
object IncludedContainer {
  @Provides
  @IntoSet
  fun provideAdapter(): String = "Foo"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val activityGraph = graph.create()
  return "OK"
}