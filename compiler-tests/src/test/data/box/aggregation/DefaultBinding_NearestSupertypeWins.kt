@DefaultBinding<GrandparentFactory<*>>
interface GrandparentFactory<T> {
  fun create(): T
}

// Narrower subtype overrides with its own @DefaultBinding
@DefaultBinding<ParentFactory<*>>
interface ParentFactory<T> : GrandparentFactory<T>

interface Other

@ContributesBinding(AppScope::class)
@Inject
class MyFactory : ParentFactory<String>, Other {
  override fun create(): String = "hello"
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  val factory: ParentFactory<*>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  val factory = graph.factory
  assertNotNull(factory)
  assertEquals("hello", factory.create())
  return "OK"
}
