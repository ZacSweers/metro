// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=2 -Xplugin-option=metro:sharding.breakCycles=true
// TEST_TARGET: jvm

// Test automatic cross-shard back-edge cycle breaking
// With keysPerShard=2, services will be distributed across multiple shards
// creating potential back-edges that need Provider indirection

// Create services that will likely span multiple shards
@Inject class ServiceA
@Inject class ServiceB(val a: ServiceA)
@Inject class ServiceC(val b: ServiceB)
@Inject class ServiceD(val c: ServiceC)

// Create a circular dependency that spans shards
// ServiceE depends on D (forward edge) and A via Provider (potential back-edge)
@Inject class ServiceE(
  val d: ServiceD,
  val aProvider: Provider<ServiceA>  // May be a back-edge if E is in higher shard than A
)

// More complex cycle with multiple dependencies
@Inject class ServiceF(
  val e: ServiceE,
  val bProvider: Provider<ServiceB>  // Another potential back-edge
)

@Inject class ServiceG(
  val f: ServiceF,
  val c: ServiceC  // Direct dependency, should work regardless of shard placement
)

@DependencyGraph
interface CycleBreakingGraph {
  val serviceA: ServiceA
  val serviceB: ServiceB
  val serviceC: ServiceC
  val serviceD: ServiceD
  val serviceE: ServiceE
  val serviceF: ServiceF
  val serviceG: ServiceG
}

fun box(): String {
  val graph = createGraph<CycleBreakingGraph>()
  
  // Verify all services are created
  assertNotNull(graph.serviceA)
  assertNotNull(graph.serviceB)
  assertNotNull(graph.serviceC)
  assertNotNull(graph.serviceD)
  assertNotNull(graph.serviceE)
  assertNotNull(graph.serviceF)
  assertNotNull(graph.serviceG)
  
  // Verify dependencies are correctly wired
  assertEquals(graph.serviceA, graph.serviceB.a)
  assertEquals(graph.serviceB, graph.serviceC.b)
  assertEquals(graph.serviceC, graph.serviceD.c)
  assertEquals(graph.serviceD, graph.serviceE.d)
  
  // Verify Provider dependencies work correctly
  // Using invoke() operator instead of get() for Kotlin Provider
  assertEquals(graph.serviceA, graph.serviceE.aProvider())
  assertEquals(graph.serviceB, graph.serviceF.bProvider())
  
  // Verify complex dependencies
  assertEquals(graph.serviceE, graph.serviceF.e)
  assertEquals(graph.serviceF, graph.serviceG.f)
  assertEquals(graph.serviceC, graph.serviceG.c)
  
  // Verify Providers return consistent instances
  val aFromProvider1 = graph.serviceE.aProvider()
  val aFromProvider2 = graph.serviceE.aProvider()
  assertEquals(aFromProvider1, aFromProvider2)
  assertEquals(graph.serviceA, aFromProvider1)
  
  val bFromProvider1 = graph.serviceF.bProvider()
  val bFromProvider2 = graph.serviceF.bProvider()
  assertEquals(bFromProvider1, bFromProvider2)
  assertEquals(graph.serviceB, bFromProvider1)
  
  // Test passes if:
  // 1. Graph compiles without StackOverflowError (cycle breaking works)
  // 2. All dependencies are correctly wired
  // 3. Provider indirection works for potential back-edges
  
  return "OK"
}