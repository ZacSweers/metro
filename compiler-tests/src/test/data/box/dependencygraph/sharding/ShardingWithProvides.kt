// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that @Provides bindings work correctly with sharding.
 *
 * Graph structure: @Provides methods providing String and Int, consumed by Service3
 * Expected shards: @Provides bindings distributed across shards
 *
 * Validation: @Provides bindings are accessible across shard boundaries
 */

@SingleIn(AppScope::class) @Inject class Service1

class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@BindingContainer
@ContributesTo(AppScope::class)
object AppModule {
  @Provides
  @SingleIn(AppScope::class)
  fun provideService2(s1: Service1): Service2 = Service2(s1)
}

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
}

fun box(): String {
  val graph = createGraph<TestGraph>()
  return when {
    graph.service3.s2.s1 == null -> "FAIL: dependency chain broken"
    graph.service3.s2.s1 !== graph.service1 -> "FAIL: not same instance"
    else -> "OK"
  }
}
