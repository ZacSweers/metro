// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

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
