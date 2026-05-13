interface Thing

@DefaultBinding<ThingFactory<*>>
interface ThingFactory<T : Thing> {
  fun create(): T
}

interface OtherInterface

class ThingInstance : Thing

@ContributesBinding(AppScope::class)
@Inject
class MyFactory : ThingFactory<ThingInstance>, OtherInterface {
  override fun create(): ThingInstance = ThingInstance()
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val factory: ThingFactory<*>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.factory
  assertNotNull(factory)
  val thing = factory.create()
  assertNotNull(thing)
  assertEquals("ThingInstance", thing::class.simpleName)
  return "OK"
}
