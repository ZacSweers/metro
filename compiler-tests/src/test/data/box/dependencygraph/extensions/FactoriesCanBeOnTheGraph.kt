import kotlin.reflect.KClass

interface ActivityFactory<T : Any> {
  fun create(): T
}

@GraphExtension
interface Activity1Graph {
  val value: Int
  @GraphExtension.Factory
  interface Factory : ActivityFactory<Activity1Graph>
}

@GraphExtension
interface Activity2Graph {
  val value: Int
  @GraphExtension.Factory
  interface Factory : ActivityFactory<Activity2Graph>
}

@DependencyGraph
interface AppGraph {
  val factories: Map<KClass<*>, ActivityFactory<*>>

  val activity1GraphFactory: Activity1Graph.Factory
  val activity2GraphFactory: Activity2Graph.Factory

  @Binds @IntoMap @ClassKey(Activity1Graph.Factory::class)
  val Activity1Graph.Factory.bind: ActivityFactory<*>

  @Binds @IntoMap @ClassKey(Activity2Graph.Factory::class)
  val Activity2Graph.Factory.bind: ActivityFactory<*>

  @Provides fun provideInt(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.factories
  assertEquals(2, factories.size)
  val factory1 = factories.getValue(Activity1Graph.Factory::class)
  assertEquals(3, (factory1 as Activity1Graph.Factory).create().value)
  val factory2 = factories.getValue(Activity2Graph.Factory::class)
  assertEquals(3, (factory2 as Activity2Graph.Factory).create().value)
  return "OK"
}