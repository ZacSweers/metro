// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// Minimal sharding test - creates a simple graph with enough bindings to trigger sharding
@Inject class Service1
@Inject class Service2(val s1: Service1)
@Inject class Service3(val s2: Service2)
@Inject class Service4(val s3: Service3)
@Inject class Service5(val s4: Service4)
@Inject class Service6(val s5: Service5)
@Inject class Service7(val s6: Service6)
@Inject class Service8(val s7: Service7)

@DependencyGraph
interface MinimalShardedGraph {
  val service1: Service1
  val service2: Service2
  val service3: Service3
  val service4: Service4
  val service5: Service5
  val service6: Service6
  val service7: Service7
  val service8: Service8
}

fun box(): String {
  val graph = createGraph<MinimalShardedGraph>()
  
  // Verify all services are accessible
  assertNotNull(graph.service1)
  assertNotNull(graph.service2)
  assertNotNull(graph.service3)
  assertNotNull(graph.service4)
  assertNotNull(graph.service5)
  assertNotNull(graph.service6)
  assertNotNull(graph.service7)
  assertNotNull(graph.service8)
  
  // Verify dependency chain
  assertEquals(graph.service1, graph.service2.s1)
  assertEquals(graph.service2, graph.service3.s2)
  assertEquals(graph.service3, graph.service4.s3)
  assertEquals(graph.service4, graph.service5.s4)
  assertEquals(graph.service5, graph.service6.s5)
  assertEquals(graph.service6, graph.service7.s6)
  assertEquals(graph.service7, graph.service8.s7)
  
  return "OK"
}