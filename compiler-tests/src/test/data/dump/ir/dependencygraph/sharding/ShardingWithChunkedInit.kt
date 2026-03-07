// KEYS_PER_GRAPH_SHARD: 4
// ENABLE_GRAPH_SHARDING: true
// CHUNK_FIELD_INITS: true
// STATEMENTS_PER_INIT_FUN: 2

/*
 * This test verifies that shard constructor initialization is chunked when
 * property count exceeds the statementsPerInitFun threshold.
 *
 * With KEYS_PER_GRAPH_SHARD=4 and STATEMENTS_PER_INIT_FUN=2:
 * - Shard1 gets A, B, C, D (4 bindings) → chunked into init(A,B) and init2(C,D)
 * - Shard2 gets E, F (2 bindings) → NOT chunked (fits directly in constructor)
 *
 * Expected: Shard1 constructor calls init() and init2()
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
