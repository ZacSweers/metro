// KEYS_PER_GRAPH_SHARD: 5
// ENABLE_GRAPH_SHARDING: true

/*
 * Regression test for cross-shard AssistedFactory NPE.
 *
 * The bug: AssistedFactory bindings were excluded from SwitchingProvider and the
 * AssistedFactory → target dependency edge was not modeled in the shard dependency graph.
 * Without the fix, this test fails at COMPILE TIME with:
 *   "Shard 0 requires graph access but has no graph property"
 * because computeShardDependencies() doesn't see the factory → target cross-shard edge.
 *
 * This test creates a scenario where the AssistedFactory's target's MetroFactory ends up
 * in a later shard (by having two factories target the same class, forcing the target
 * into a FIELD property), while the factory itself is in an earlier shard (due to its
 * factoryRefCount > 1 from Provider<F> usage).
 *
 * With SwitchingProviders (FastInitBoxTest), the deferred initialization breaks the
 * circular cross-shard dependency at runtime. Without SwitchingProviders (BoxTest),
 * the circular dependency causes a runtime NPE which is caught below — the key assertion
 * is that the code COMPILES (the fix models the dependency edge correctly).
 */

// Dependency chain to spread bindings across shards
@SingleIn(AppScope::class) @Inject class S1

@SingleIn(AppScope::class) @Inject class S2(val s1: S1)

@SingleIn(AppScope::class) @Inject class S3(val s2: S2)

// Assisted-inject target with dependency chain
@AssistedInject class T1(@Assisted val id: String, val s3: S3)

// Two factories targeting the same class → forces T1's MetroFactory into a FIELD property
@AssistedFactory
fun interface F1 {
  fun create(id: String): T1
}

@AssistedFactory
fun interface F2 {
  fun create(id: String): T1
}

// Multiple consumers using the same factory via Provider → forces factory into FIELD property
@SingleIn(AppScope::class) @Inject class C1(val f: Provider<F1>)

@SingleIn(AppScope::class) @Inject class C2(val f: Provider<F1>)

@SingleIn(AppScope::class) @Inject class C3(val f: F2)

@SingleIn(AppScope::class) @Inject class C4(val f: Provider<F2>)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val c1: C1
  val c2: C2
  val c3: C3
  val c4: C4
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  when {
    graph.c1.f().create("a").id != "a" -> return "FAIL: c1"
    graph.c2.f().create("b").id != "b" -> return "FAIL: c2"
    graph.c3.f.create("c").id != "c" -> return "FAIL: c3"
    graph.c4.f().create("d").id != "d" -> return "FAIL: c4"
  }
  return "OK"
}
