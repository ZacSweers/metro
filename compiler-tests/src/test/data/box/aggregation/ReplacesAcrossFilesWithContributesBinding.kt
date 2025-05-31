// FILE: Cache.kt
interface Cache

// FILE: RealCache.kt
@ContributesBinding(AppScope::class)
object RealCache : Cache

// FILE: TestCache.kt
@ContributesBinding(AppScope::class, replaces = [RealCache::class])
object TestCache : Cache

// FILE: TestGraph.kt
@DependencyGraph(AppScope::class)
interface TestGraph {
  val cache: Cache
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  assert(graph.cache is TestCache)
  return "OK"
}
