// ENABLE_DERIVED_BINDINGS

// When a parent and its graph extension both request a key from a parent-scoped generic provider,
// they share the parent's instance.
class Cache<T>(val id: Int)

class Counter {
  var count = 0
}

@Inject class CacheUser(val cache: Cache<String>)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringCache: Cache<String>
  val counter: Counter
  val childFactory: ChildGraph.Factory

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}

@GraphExtension
interface ChildGraph {
  val stringCache: Cache<String>
  val user: CacheUser
  val intCache: Cache<Int>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val child = parent.childFactory.create()
  val strings = parent.stringCache
  assertSame(strings, child.stringCache)
  assertSame(strings, child.user.cache)
  assertSame(child.intCache, parent.childFactory.create().intCache)
  assertNotSame<Any>(strings, child.intCache)
  assertEquals(2, parent.counter.count)
  return "OK"
}
