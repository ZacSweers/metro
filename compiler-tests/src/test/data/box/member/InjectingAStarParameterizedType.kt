// https://github.com/ZacSweers/metro/issues/1329
@Qualifier annotation class ApplicationContext

class Context

class GenericTest

abstract class BaseMainActivity<T> {
  @Inject @ApplicationContext lateinit var context: Context
}

class MainActivity : BaseMainActivity<GenericTest>() {}

@DependencyGraph
interface AppComponent {
  fun inject(activity: BaseMainActivity<*>)

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides @ApplicationContext context: Context): AppComponent
  }
}

fun box(): String {
  val context = Context()
  val graph = createGraphFactory<AppComponent.Factory>().create(context)
  val activity = MainActivity()
  graph.inject(activity)
  assertSame(context, activity.context)
  return "OK"
}
