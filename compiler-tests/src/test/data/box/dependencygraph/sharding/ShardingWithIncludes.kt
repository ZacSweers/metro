// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_GRAPH_SHARDING: true

/*
 * This test verifies that @Includes graph dependency properties are correctly
 * accessed from shard classes.
 *
 * When sharding is enabled, bindings are distributed across nested shard classes
 * (Shard1, Shard2, etc.) inside the main Impl class. @Includes parameters are
 * stored as properties on the main Impl class, not in any shard. When a shard
 * binding needs to access an @Includes dependency, the generated code must go
 * through `this.graph.property` (shard -> main class) rather than directly
 * accessing `this.property` on the shard.
 *
 * This is a regression test for a bug where GraphDependency and BoundInstance
 * bindings bypassed generatePropertyAccess() and used irGet(thisReceiver)
 * directly, causing NoSuchFieldError at runtime when the shard tried to access
 * a field that only existed on the main graph class.
 */

interface ParentDependencies {
  val greeting: String
}

@SingleIn(AppScope::class) @Inject class Service1(val greeting: String)

@SingleIn(AppScope::class) @Inject class Service2(val greeting: String)

@SingleIn(AppScope::class) @Inject class Service3(val s1: Service1, val s2: Service2)

@DependencyGraph(scope = AppScope::class)
interface TestGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Includes parent: ParentDependencies): TestGraph
  }
}

fun box(): String {
  val parent = object : ParentDependencies {
    override val greeting: String = "Hello"
  }
  val graph = createGraphFactory<TestGraph.Factory>().create(parent)

  return when {
    graph.service1.greeting != "Hello" -> "FAIL: service1 greeting wrong"
    graph.service2.greeting != "Hello" -> "FAIL: service2 greeting wrong"
    graph.service3.s1.greeting != "Hello" -> "FAIL: service3.s1 greeting wrong"
    graph.service3.s2.greeting != "Hello" -> "FAIL: service3.s2 greeting wrong"
    graph.service3.s1 !== graph.service1 -> "FAIL: service1 not same instance"
    graph.service3.s2 !== graph.service2 -> "FAIL: service2 not same instance"
    else -> "OK"
  }
}
