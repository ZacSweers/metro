interface Cache<out T> {
  val value: T
}

@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
class ValueCache<T : Comparable<T>>(override val value: T) : Cache<T>

interface PairCache<A, B> {
  val first: A
  val second: B
}

@ContributesBinding(AppScope::class)
@Inject
class ValuePairCache<A, B : A>(override val first: A, override val second: B) : PairCache<A, B>

@DependencyGraph(AppScope::class)
interface AppGraph {
  val stringCache: Cache<String>
  val intCache: Cache<Int>
  val pairCache: PairCache<CharSequence, String>

  @Provides fun string(): String = "value"
  @Provides fun int(): Int = 42
  @Provides fun chars(): CharSequence = "first"
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals("value", graph.stringCache.value)
  assertEquals(42, graph.intCache.value)
  assertSame(graph.stringCache, graph.stringCache)
  assertSame(graph.intCache, graph.intCache)
  assertEquals("first", graph.pairCache.first)
  assertEquals("value", graph.pairCache.second)
  return "OK"
}
