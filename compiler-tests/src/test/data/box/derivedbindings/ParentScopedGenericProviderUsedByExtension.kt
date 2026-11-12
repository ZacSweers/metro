// ENABLE_DERIVED_BINDINGS

// A parent-scoped generic provider that only a graph extension requests still lives in the parent.
// Every extension instance sees one instance per key, and separate keys get separate instances.
class Cache<T>(val id: Int)

class Counter {
  var count = 0
}

@BindingContainer
object Caches {
  @Provides
  @SingleIn(AppScope::class)
  fun <T> cache(counter: Counter): Cache<T> = Cache(++counter.count)
}

@DependencyGraph(AppScope::class, bindingContainers = [Caches::class])
interface AppGraph {
  val counter: Counter
  val childFactory: ChildGraph.Factory

  @Provides @SingleIn(AppScope::class) fun counter(): Counter = Counter()
}

@GraphExtension
interface ChildGraph {
  val stringCache: Cache<String>
  val stringCacheAgain: Cache<String>
  val intCache: Cache<Int>
  val intCacheProvider: Provider<Cache<Int>>

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val parent = createGraph<AppGraph>()
  val first = parent.childFactory.create()
  val second = parent.childFactory.create()
  val strings = first.stringCache
  assertSame(strings, first.stringCacheAgain)
  assertSame(strings, second.stringCache)
  val ints = first.intCache
  assertSame(ints, second.intCache)
  assertSame(ints, second.intCacheProvider())
  assertNotSame<Any>(strings, ints)
  assertNotEquals(strings.id, ints.id)
  assertEquals(2, parent.counter.count)
  return "OK"
}
