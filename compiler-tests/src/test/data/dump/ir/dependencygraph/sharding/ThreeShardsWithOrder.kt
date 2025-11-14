// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

// This test verifies initialization order with 3 shards
// Shard1: S1, S2
// Shard2: S3, S4 (S4 depends on S1 from Shard1)
// Shard3: S5 (depends on S4 from Shard2)
// Order must be: Shard1 → Shard2 → Shard3

@SingleIn(AppScope::class) @Inject class S1

@SingleIn(AppScope::class) @Inject class S2

@SingleIn(AppScope::class) @Inject class S3

@SingleIn(AppScope::class) @Inject class S4(val s1: S1)

@SingleIn(AppScope::class) @Inject class S5(val s4: S4)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val s1: S1
  val s2: S2
  val s3: S3
  val s4: S4
  val s5: S5
}
