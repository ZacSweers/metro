// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

// This test verifies cross-shard dependencies and initialization order
// Shard1: Service1, Service2
// Shard2: Service3 (depends on Service2 from Shard1) - must initialize after Shard1

@SingleIn(AppScope::class) @Inject class Service1

@SingleIn(AppScope::class) @Inject class Service2

@SingleIn(AppScope::class) @Inject class Service3(val s1: Service1, val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}
