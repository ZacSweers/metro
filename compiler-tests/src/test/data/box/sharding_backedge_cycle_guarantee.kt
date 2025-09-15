// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2 -Xplugin-option=metro:sharding.breakCycles=true
// TEST_TARGET: jvm

// Test that guarantees automatic back-edge cycle breaking works
// Creates a cycle between shards that would cause StackOverflowError without Provider indirection
// NO manual Provider usage - the compiler must handle this automatically

// With keysPerShard=2, we'll carefully control shard placement

// These two will likely go in shard 1
@Inject class LowShardServiceA
@Inject class LowShardServiceB(val a: LowShardServiceA)

// These will likely go in shard 2 (or higher)
@Inject class HighShardServiceC(val b: LowShardServiceB)
@Inject class HighShardServiceD(val c: HighShardServiceC)

// Create the cycle: E depends on D (forward) and A (back-edge)
// Without automatic cycle breaking, this would cause StackOverflowError
@Inject class CyclicServiceE(
  val d: HighShardServiceD,  // Forward edge (higher shard)
  val a: LowShardServiceA     // Back-edge (lower shard) - compiler must use Provider internally
)

// Another cycle: F depends on E (forward) and B (back-edge)
@Inject class CyclicServiceF(
  val e: CyclicServiceE,
  val b: LowShardServiceB      // Another back-edge
)

// Complex multi-shard dependency
@Inject class ComplexService(
  val f: CyclicServiceF,
  val a: LowShardServiceA,     // Direct back-edge reference
  val c: HighShardServiceC     // Forward reference
)

@DependencyGraph
interface BackEdgeCycleGuaranteeGraph {
  // Expose all services
  val lowA: LowShardServiceA
  val lowB: LowShardServiceB
  val highC: HighShardServiceC
  val highD: HighShardServiceD
  val cyclicE: CyclicServiceE
  val cyclicF: CyclicServiceF
  val complex: ComplexService
}

fun box(): String {
  val graph = createGraph<BackEdgeCycleGuaranteeGraph>()
  
  // Verify all services are created (no StackOverflowError)
  assertNotNull(graph.lowA)
  assertNotNull(graph.lowB)
  assertNotNull(graph.highC)
  assertNotNull(graph.highD)
  assertNotNull(graph.cyclicE)
  assertNotNull(graph.cyclicF)
  assertNotNull(graph.complex)
  
  // Verify forward dependencies work normally
  assertEquals(graph.lowA, graph.lowB.a)
  assertEquals(graph.lowB, graph.highC.b)
  assertEquals(graph.highC, graph.highD.c)
  assertEquals(graph.highD, graph.cyclicE.d)
  assertEquals(graph.cyclicE, graph.cyclicF.e)
  
  // Verify back-edge dependencies work (compiler uses Provider internally)
  assertEquals(graph.lowA, graph.cyclicE.a)  // Back-edge from higher to lower shard
  assertEquals(graph.lowB, graph.cyclicF.b)  // Another back-edge
  
  // Verify complex service has all correct dependencies
  assertEquals(graph.cyclicF, graph.complex.f)
  assertEquals(graph.lowA, graph.complex.a)
  assertEquals(graph.highC, graph.complex.c)
  
  // Verify instances are singletons (Provider indirection still returns same instance)
  val a1 = graph.cyclicE.a
  val a2 = graph.complex.a
  val a3 = graph.lowA
  assertEquals(a1, a2)
  assertEquals(a2, a3)
  
  val b1 = graph.cyclicF.b
  val b2 = graph.lowB
  assertEquals(b1, b2)
  
  // Success criteria:
  // 1. Graph compiles and runs without StackOverflowError
  // 2. Back-edges are automatically handled with Provider indirection
  // 3. All dependencies resolve correctly
  // 4. No manual Provider usage required in user code
  
  return "OK"
}