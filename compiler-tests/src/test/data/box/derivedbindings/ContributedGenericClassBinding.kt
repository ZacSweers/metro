// MODULE: lib
interface Cache<T> {
  val label: String
}

@ContributesBinding(AppScope::class)
@Inject
class LruCache<T>(override val label: String) : Cache<T>

// MODULE: main(lib)
@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringCache: Cache<String>
  val intCache: Cache<Int>

  @Provides fun label(): String = "label"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.stringCache is LruCache<*>)
  assertTrue(graph.intCache is LruCache<*>)
  assertEquals("label", graph.stringCache.label)
  return "OK"
}
