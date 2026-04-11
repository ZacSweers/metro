// https://github.com/ZacSweers/metro/issues/2021
// MODULE: lib
class HttpClient(val name: String)

@BindingContainer
object AppBindingContainer {
  @Provides @SingleIn(AppScope::class) fun httpClient(): HttpClient = HttpClient("real")
}

@DependencyGraph(scope = AppScope::class, bindingContainers = [AppBindingContainer::class])
interface AppGraph

class SomeActivity {
  @Inject lateinit var client: HttpClient

  fun print(): String {
    return client.name
  }
}

@GraphExtension
interface ActivityGraph {
  fun inject(activity: SomeActivity)

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(@Provides instance: SomeActivity): ActivityGraph
  }
}

// MODULE: main(lib)
@BindingContainer
object TestBindingContainer {
  @Provides @SingleIn(AppScope::class) @JvmStatic fun httpClient(): HttpClient = HttpClient("fake")
}

fun box(): String {
  val graph = createDynamicGraph<AppGraph>(TestBindingContainer)
  val activity = SomeActivity()
  val ext = graph.asContribution<ActivityGraph.Factory>().create(activity)
  ext.inject(activity)
  assertEquals("fake", activity.print())
  return "OK"
}
