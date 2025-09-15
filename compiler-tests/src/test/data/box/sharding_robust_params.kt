// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2
// TEST_TARGET: jvm

// Test that cross-shard access works correctly with robust parameter handling
// The implementation uses stored symbols instead of relying on parameter names

// Create enough services to span multiple shards (with keysPerShard=2)
@Inject class RobustService1
@Inject class RobustService2
@Inject class RobustService3(val s1: RobustService1)
@Inject class RobustService4(val s2: RobustService2)
@Inject class RobustService5(val s3: RobustService3, val s4: RobustService4)
@Inject class RobustService6(val s5: RobustService5)

// Cross-shard dependency service
@Inject class CrossShardService(
  val s1: RobustService1,  // Likely in a lower shard
  val s6: RobustService6   // Likely in a higher shard
)

@DependencyGraph
interface RobustParamsGraph {
  val service1: RobustService1
  val service2: RobustService2
  val service3: RobustService3
  val service4: RobustService4
  val service5: RobustService5
  val service6: RobustService6
  val crossShardService: CrossShardService
}

fun box(): String {
  val graph = createGraph<RobustParamsGraph>()
  
  // Verify all services are created
  assertNotNull(graph.service1)
  assertNotNull(graph.service2)
  assertNotNull(graph.service3)
  assertNotNull(graph.service4)
  assertNotNull(graph.service5)
  assertNotNull(graph.service6)
  assertNotNull(graph.crossShardService)
  
  // Verify dependencies are correctly wired across shards
  assertEquals(graph.service1, graph.service3.s1)
  assertEquals(graph.service2, graph.service4.s2)
  assertEquals(graph.service3, graph.service5.s3)
  assertEquals(graph.service4, graph.service5.s4)
  assertEquals(graph.service5, graph.service6.s5)
  
  // Verify cross-shard dependencies work
  assertEquals(graph.service1, graph.crossShardService.s1)
  assertEquals(graph.service6, graph.crossShardService.s6)
  
  // Test passes if:
  // 1. Graph compiles successfully (robust parameter handling works)
  // 2. All cross-shard dependencies are correctly resolved
  // 3. No runtime errors from incorrect parameter access
  
  return "OK"
}