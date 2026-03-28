// Verify that Provider<T> and Lazy<T> wrapping works correctly with contribution providers.

interface Dependency

@ContributesBinding(AppScope::class)
@Inject
class DependencyImpl : Dependency

@Inject
class Consumer(
  val provider: Provider<Dependency>,
  val lazy: Lazy<Dependency>,
)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val consumer: Consumer
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val consumer = graph.consumer
  val fromProvider = consumer.provider()
  val fromLazy = consumer.lazy.value
  assertNotNull(fromProvider)
  assertNotNull(fromLazy)
  assertEquals("DependencyImpl", fromProvider::class.simpleName)
  assertEquals("DependencyImpl", fromLazy::class.simpleName)
  return "OK"
}
