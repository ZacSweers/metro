// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that cross-shard dependencies work correctly.
 *
 * Graph structure: Service3 depends on both Service1 and Service2
 * Expected shards: 2 shards with cross-shard references
 * - Shard1: Service1, Service2
 * - Shard2: Service3 (depends on services in Shard1, must initialize after Shard1)
 *
 * Validation: Generated IR shows proper cross-shard field access and initialization order
 */

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2

@SingleIn(AppScope::class) @Inject class Service3(val s1: Service1, val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}
