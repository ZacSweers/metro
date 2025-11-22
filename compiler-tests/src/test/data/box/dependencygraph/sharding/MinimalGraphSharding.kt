// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies basic graph sharding with a linear dependency chain.
 *
 * Graph structure: Service1 → Service2 → Service3 (linear chain)
 * Expected shards: 2 shards
 * - Shard1: Service1, Service2
 * - Shard2: Service3
 *
 * Validation: Dependency chain works correctly across shard boundaries
 */

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service3: Service3
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  val service = graph.service3

  // Verify the dependency chain works through shards
  return when {
    service.s2.s1 == null -> "FAIL: null in chain"
    else -> "OK"
  }
}
