// KEYS_PER_GRAPH_SHARD: 3
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that sharding is skipped when the graph is too small.
 *
 * Graph structure: Service1 → Service2 → Service3 (3 bindings total)
 * Shard size: 3 (equal to total binding count)
 * Expected shards: None (graph size ≤ shard size, sharding not needed)
 *
 * Validation: Generated IR shows no shard classes despite sharding being enabled
 */

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service3: Service3
}
