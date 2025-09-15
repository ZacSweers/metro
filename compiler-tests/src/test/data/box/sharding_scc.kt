// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// SCC (Strongly Connected Components) test - bindings with cycles must stay in same shard

// Create a strongly connected component (cycle)
@Inject class ComponentA(val provider: Provider<ComponentC>)
@Inject class ComponentB(val a: ComponentA)
@Inject class ComponentC(val b: ComponentB)

// Another SCC with a different cycle
@Inject class ServiceX(val provider: Provider<ServiceZ>)
@Inject class ServiceY(val x: ServiceX)
@Inject class ServiceZ(val y: ServiceY)

// Independent linear chain (should be able to split across shards)
@Inject class LinearService1
@Inject class LinearService2(val s1: LinearService1)
@Inject class LinearService3(val s2: LinearService2)
@Inject class LinearService4(val s3: LinearService3)
@Inject class LinearService5(val s4: LinearService4)
@Inject class LinearService6(val s5: LinearService5)

// Bridge service that depends on both SCCs
@Inject class BridgeService(
  val componentA: ComponentA,
  val serviceX: ServiceX,
  val linear: LinearService6
)

@DependencyGraph
interface SccShardedGraph {
  // First SCC
  val componentA: ComponentA
  val componentB: ComponentB
  val componentC: ComponentC
  
  // Second SCC
  val serviceX: ServiceX
  val serviceY: ServiceY
  val serviceZ: ServiceZ
  
  // Linear chain
  val linearService1: LinearService1
  val linearService2: LinearService2
  val linearService3: LinearService3
  val linearService4: LinearService4
  val linearService5: LinearService5
  val linearService6: LinearService6
  
  // Bridge
  val bridgeService: BridgeService
}

fun box(): String {
  val graph = createGraph<SccShardedGraph>()
  
  // Verify first SCC is wired correctly
  assertNotNull(graph.componentA)
  assertNotNull(graph.componentB)
  assertNotNull(graph.componentC)
  assertEquals(graph.componentA, graph.componentB.a)
  assertEquals(graph.componentB, graph.componentC.b)
  assertEquals(graph.componentC, graph.componentA.provider.get())
  
  // Verify second SCC is wired correctly
  assertNotNull(graph.serviceX)
  assertNotNull(graph.serviceY)
  assertNotNull(graph.serviceZ)
  assertEquals(graph.serviceX, graph.serviceY.x)
  assertEquals(graph.serviceY, graph.serviceZ.y)
  assertEquals(graph.serviceZ, graph.serviceX.provider.get())
  
  // Verify linear chain
  assertNotNull(graph.linearService1)
  assertNotNull(graph.linearService2)
  assertNotNull(graph.linearService3)
  assertNotNull(graph.linearService4)
  assertNotNull(graph.linearService5)
  assertNotNull(graph.linearService6)
  assertEquals(graph.linearService1, graph.linearService2.s1)
  assertEquals(graph.linearService2, graph.linearService3.s2)
  assertEquals(graph.linearService3, graph.linearService4.s3)
  assertEquals(graph.linearService4, graph.linearService5.s4)
  assertEquals(graph.linearService5, graph.linearService6.s5)
  
  // Verify bridge service connects both SCCs and linear chain
  assertNotNull(graph.bridgeService)
  assertEquals(graph.componentA, graph.bridgeService.componentA)
  assertEquals(graph.serviceX, graph.bridgeService.serviceX)
  assertEquals(graph.linearService6, graph.bridgeService.linear)
  
  return "OK"
}