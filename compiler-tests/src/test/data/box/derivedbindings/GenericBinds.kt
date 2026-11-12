// ENABLE_DERIVED_BINDINGS

// Generic @Binds functions alias each requested key to the matching generic @Inject class.
interface Cache<T> {
  val label: String
}

interface Store<T> {
  val label: String
}

@Inject class LruCache<T>(override val label: String) : Cache<T>

@Inject class DiskStore<T>(override val label: String) : Store<T>

@BindingContainer
interface CacheBindings {
  @Binds fun <T> lru(impl: LruCache<T>): Cache<T>

  @Binds fun <T> DiskStore<T>.bindStore(): Store<T>
}

@DependencyGraph(bindingContainers = [CacheBindings::class])
interface AppGraph {
  val stringCache: Cache<String>
  val intCache: Cache<Int>
  val listStore: Store<List<String>>

  @Provides fun label(): String = "label"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertTrue(graph.stringCache is LruCache<*>)
  assertTrue(graph.intCache is LruCache<*>)
  assertTrue(graph.listStore is DiskStore<*>)
  assertEquals("label", graph.stringCache.label)
  assertEquals("label", graph.listStore.label)
  return "OK"
}
