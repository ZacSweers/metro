// KEYS_PER_GRAPH_SHARD: 4
// ENABLE_GRAPH_SHARDING: true
// CHUNK_FIELD_INITS: true
// STATEMENTS_PER_INIT_FUN: 2

/*
 * This test verifies that shard initialize() functions are chunked when they exceed
 * the statementsPerInitFun threshold.
 *
 * With KEYS_PER_GRAPH_SHARD=4 and STATEMENTS_PER_INIT_FUN=2:
 * - Shard1 gets A, B, C, D (4 bindings) → chunked into init1(A,B) and init2(C,D)
 * - Shard2 gets E, F (2 bindings) → NOT chunked (fits in one function)
 *
 * Expected: Shard1.initialize() calls init1() and init2()
 */

@SingleIn(AppScope::class) @Inject class A
@SingleIn(AppScope::class) @Inject class B(val a: A)
@SingleIn(AppScope::class) @Inject class C(val b: B)
@SingleIn(AppScope::class) @Inject class D(val c: C)
@SingleIn(AppScope::class) @Inject class E(val d: D)
@SingleIn(AppScope::class) @Inject class F(val e: E)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val f: F
}
