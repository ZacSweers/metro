// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// Cross-shard dependencies test - verifies that dependencies across shards work correctly

// Shard 1 candidates (first 5 bindings)
@Inject class Shard1Service1
@Inject class Shard1Service2(val s1: Shard1Service1)
@Inject class Shard1Service3(val s2: Shard1Service2)
@Inject class Shard1Service4(val s3: Shard1Service3)
@Inject class Shard1Service5(val s4: Shard1Service4)

// Shard 2 candidates (next 5 bindings)
@Inject class Shard2Service1(val shard1Dep: Shard1Service5) // Cross-shard dependency
@Inject class Shard2Service2(val s1: Shard2Service1)
@Inject class Shard2Service3(val s2: Shard2Service2)
@Inject class Shard2Service4(val s3: Shard2Service3)
@Inject class Shard2Service5(val s4: Shard2Service4)

// Shard 3 candidates with multiple cross-shard dependencies
@Inject class Shard3Service1(
  val shard1Dep: Shard1Service3,  // Dependency on shard 1
  val shard2Dep: Shard2Service2   // Dependency on shard 2
)
@Inject class Shard3Service2(val s1: Shard3Service1)
@Inject class Shard3Service3(val s2: Shard3Service2)
@Inject class Shard3Service4(val s3: Shard3Service3)
@Inject class Shard3Service5(val s4: Shard3Service4)

// Main graph service that depends on all shards
@Inject class OrchestratorService(
  val shard1: Shard1Service5,
  val shard2: Shard2Service5,
  val shard3: Shard3Service5
)

// Binding container with provisions that reference cross-shard dependencies
@BindingContainer
class CrossShardBindings {
  @Provides
  fun provideAggregatedData(
    s1: Shard1Service1,
    s2: Shard2Service1,
    s3: Shard3Service1
  ): String = "${s1.hashCode()}-${s2.hashCode()}-${s3.hashCode()}"
}

@DependencyGraph(bindingContainers = [CrossShardBindings::class])
interface CrossShardGraph {
  // Shard 1 services
  val shard1Service1: Shard1Service1
  val shard1Service2: Shard1Service2
  val shard1Service3: Shard1Service3
  val shard1Service4: Shard1Service4
  val shard1Service5: Shard1Service5
  
  // Shard 2 services
  val shard2Service1: Shard2Service1
  val shard2Service2: Shard2Service2
  val shard2Service3: Shard2Service3
  val shard2Service4: Shard2Service4
  val shard2Service5: Shard2Service5
  
  // Shard 3 services
  val shard3Service1: Shard3Service1
  val shard3Service2: Shard3Service2
  val shard3Service3: Shard3Service3
  val shard3Service4: Shard3Service4
  val shard3Service5: Shard3Service5
  
  // Orchestrator
  val orchestrator: OrchestratorService
  
  // Module provision with cross-shard deps
  val aggregatedData: String
}

fun box(): String {
  val graph = createGraph<CrossShardGraph>()
  
  // Verify all services are created
  assertNotNull(graph.shard1Service1)
  assertNotNull(graph.shard1Service5)
  assertNotNull(graph.shard2Service1)
  assertNotNull(graph.shard2Service5)
  assertNotNull(graph.shard3Service1)
  assertNotNull(graph.shard3Service5)
  assertNotNull(graph.orchestrator)
  
  // Verify cross-shard dependencies
  // Shard2Service1 depends on Shard1Service5
  assertEquals(graph.shard1Service5, graph.shard2Service1.shard1Dep)
  
  // Shard3Service1 depends on both Shard1Service3 and Shard2Service2
  assertEquals(graph.shard1Service3, graph.shard3Service1.shard1Dep)
  assertEquals(graph.shard2Service2, graph.shard3Service1.shard2Dep)
  
  // Orchestrator depends on all shards
  assertEquals(graph.shard1Service5, graph.orchestrator.shard1)
  assertEquals(graph.shard2Service5, graph.orchestrator.shard2)
  assertEquals(graph.shard3Service5, graph.orchestrator.shard3)
  
  // Verify module provision works with cross-shard dependencies
  assertNotNull(graph.aggregatedData)
  assertTrue(graph.aggregatedData.contains(graph.shard1Service1.hashCode().toString()))
  assertTrue(graph.aggregatedData.contains(graph.shard2Service1.hashCode().toString()))
  assertTrue(graph.aggregatedData.contains(graph.shard3Service1.hashCode().toString()))
  
  return "OK"
}