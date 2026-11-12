// ENABLE_DERIVED_BINDINGS

// A scoped generic provider creates one instance per key and separate instances for separate keys.
class Cache<T>(val id: Int)

class Counter {
  var count = 0
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringCache: Cache<String>
  val stringCacheAgain: Cache<String>
  val intCache: Cache<Int>

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()

  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val first = graph.stringCache
  assertSame(first, graph.stringCache)
  assertSame(first, graph.stringCacheAgain)
  val ints = graph.intCache
  assertSame(ints, graph.intCache)
  assertNotSame<Any>(first, ints)
  assertNotEquals(first.id, ints.id)
  return "OK"
}
