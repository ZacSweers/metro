// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that SCCs (cycles) are kept together in the same shard.
 *
 * Graph structure: E → (C ↔ D) → B → A
 * where C and D form a cycle broken by Provider<D>.
 *
 * C and D MUST stay in the same shard because:
 * 1. They form a strongly connected component (SCC)
 * 2. Provider<D> needs the actual D instance from the same shard
 *
 * With maxPerShard=2, expected shards:
 * - Shard with A and B (or A alone and B alone)
 * - Shard with C and D together (cycle preserved)
 * - Shard with E
 */

import dev.zacsweers.metro.Provider

@SingleIn(AppScope::class)
@Inject
class A

@SingleIn(AppScope::class)
@Inject
class B(val a: A)

@SingleIn(AppScope::class)
@Inject
class C(val b: B, val d: Provider<D>) {
  fun getValue(): String = "C+${d().getValue()}"
}

@SingleIn(AppScope::class)
@Inject
class D(val c: C) {
  fun getValue(): String = "D"
}

@SingleIn(AppScope::class)
@Inject
class E(val c: C)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val e: E
}

fun box(): String {
  val graph = createGraph<TestGraph>()

  // Verify the cycle works through the chain
  val result = graph.e.c.getValue()

  return when {
    result != "C+D" -> "FAIL: expected C+D but got $result"
    else -> "OK"
  }
}
