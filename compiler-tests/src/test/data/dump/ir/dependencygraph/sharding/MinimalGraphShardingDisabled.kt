// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: false

/*
 * This test verifies that sharding is disabled when ENABLE_GRAPH_SHARDING is false.
 *
 * Graph structure: Service1 → Service2 → Service3 (same as MinimalGraphSharding)
 * Expected shards: None (sharding disabled)
 *
 * Validation: Generated IR shows no shard classes, all initialization in main graph class
 */

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service3: Service3
}
