// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies correct initialization order with multiple shards.
 *
 * Graph structure: S1 → S4 → S5 (dependency chain), plus S2 and S3 (isolated)
 * Expected shards: 3 shards
 * - Shard1: S1, S2
 * - Shard2: S3, S4 (S4 depends on S1 from Shard1)
 * - Shard3: S5 (depends on S4 from Shard2)
 *
 * Validation: Shards initialize in correct topological order, dependencies preserved
 */

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

fun box(): String {
  val graph = createGraph<TestGraph>()
  return when {
    graph.s5.s4.s1 == null -> "FAIL: dependency chain broken"
    graph.s5.s4.s1 !== graph.s1 -> "FAIL: not same instance"
    else -> "OK"
  }
}
