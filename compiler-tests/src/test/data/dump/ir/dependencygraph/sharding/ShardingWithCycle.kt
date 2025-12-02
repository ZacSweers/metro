// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true
// DONT_SORT_DECLARATIONS

/*
 * This test verifies that SCCs (cycles) are kept together in the same shard.
 *
 * Graph structure: E → (C ↔ D) → B → A
 * where C and D form a cycle broken by Provider<D>.
 *
 * C and D MUST stay in the same shard because they form an SCC.
 * IR dump should show C and D in the same Shard class.
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
class C(val b: B, val d: Provider<D>)

@SingleIn(AppScope::class)
@Inject
class D(val c: C)

@SingleIn(AppScope::class)
@Inject
class E(val c: C)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val e: E
}
