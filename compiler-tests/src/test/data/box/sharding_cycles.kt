// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=5
// TEST_TARGET: jvm

// Cycle detection and breaking test - verifies Provider-based cycle breaking in sharded graphs

// Simple cycle within a shard
@Inject class CycleA(val provider: Provider<CycleB>)
@Inject class CycleB(val a: CycleA)

// Complex cycle spanning multiple types
@Inject class ComplexCycle1(val provider: Provider<ComplexCycle3>)
@Inject class ComplexCycle2(val c1: ComplexCycle1)
@Inject class ComplexCycle3(val c2: ComplexCycle2)

// Cycle with external dependency
@Inject class ExternalDep
@Inject class CycleWithExternal1(
  val external: ExternalDep,
  val provider: Provider<CycleWithExternal2>
)
@Inject class CycleWithExternal2(val c1: CycleWithExternal1)

// Multiple independent cycles
@Inject class IndependentCycle1A(val provider: Provider<IndependentCycle1B>)
@Inject class IndependentCycle1B(val a: IndependentCycle1A)

@Inject class IndependentCycle2A(val provider: Provider<IndependentCycle2B>)
@Inject class IndependentCycle2B(val a: IndependentCycle2A)

// Lazy cycle resolution
@Inject class LazyCycle1(val lazy: Lazy<LazyCycle2>)
@Inject class LazyCycle2(val c1: LazyCycle1)

// Mixed Provider and Lazy
@Inject class MixedCycle1(val provider: Provider<MixedCycle3>)
@Inject class MixedCycle2(val c1: MixedCycle1, val lazy: Lazy<MixedCycle3>)
@Inject class MixedCycle3(val c2: MixedCycle2)

// Service that depends on multiple cycles
@Inject class CycleConsumer(
  val cycleA: CycleA,
  val complexCycle1: ComplexCycle1,
  val cycleWithExternal1: CycleWithExternal1,
  val independentCycle1A: IndependentCycle1A,
  val independentCycle2A: IndependentCycle2A,
  val lazyCycle1: LazyCycle1,
  val mixedCycle1: MixedCycle1
)

@DependencyGraph
interface CyclesShardedGraph {
  // Simple cycle
  val cycleA: CycleA
  val cycleB: CycleB
  
  // Complex cycle
  val complexCycle1: ComplexCycle1
  val complexCycle2: ComplexCycle2
  val complexCycle3: ComplexCycle3
  
  // Cycle with external
  val externalDep: ExternalDep
  val cycleWithExternal1: CycleWithExternal1
  val cycleWithExternal2: CycleWithExternal2
  
  // Independent cycles
  val independentCycle1A: IndependentCycle1A
  val independentCycle1B: IndependentCycle1B
  val independentCycle2A: IndependentCycle2A
  val independentCycle2B: IndependentCycle2B
  
  // Lazy cycle
  val lazyCycle1: LazyCycle1
  val lazyCycle2: LazyCycle2
  
  // Mixed cycle
  val mixedCycle1: MixedCycle1
  val mixedCycle2: MixedCycle2
  val mixedCycle3: MixedCycle3
  
  // Consumer
  val cycleConsumer: CycleConsumer
}

fun box(): String {
  val graph = createGraph<CyclesShardedGraph>()
  
  // Verify simple cycle
  assertNotNull(graph.cycleA)
  assertNotNull(graph.cycleB)
  assertEquals(graph.cycleA, graph.cycleB.a)
  assertEquals(graph.cycleB, graph.cycleA.provider.get())
  
  // Verify complex cycle
  assertNotNull(graph.complexCycle1)
  assertNotNull(graph.complexCycle2)
  assertNotNull(graph.complexCycle3)
  assertEquals(graph.complexCycle1, graph.complexCycle2.c1)
  assertEquals(graph.complexCycle2, graph.complexCycle3.c2)
  assertEquals(graph.complexCycle3, graph.complexCycle1.provider.get())
  
  // Verify cycle with external dependency
  assertNotNull(graph.externalDep)
  assertNotNull(graph.cycleWithExternal1)
  assertNotNull(graph.cycleWithExternal2)
  assertEquals(graph.externalDep, graph.cycleWithExternal1.external)
  assertEquals(graph.cycleWithExternal1, graph.cycleWithExternal2.c1)
  assertEquals(graph.cycleWithExternal2, graph.cycleWithExternal1.provider.get())
  
  // Verify independent cycles work independently
  assertNotNull(graph.independentCycle1A)
  assertNotNull(graph.independentCycle1B)
  assertEquals(graph.independentCycle1A, graph.independentCycle1B.a)
  assertEquals(graph.independentCycle1B, graph.independentCycle1A.provider.get())
  
  assertNotNull(graph.independentCycle2A)
  assertNotNull(graph.independentCycle2B)
  assertEquals(graph.independentCycle2A, graph.independentCycle2B.a)
  assertEquals(graph.independentCycle2B, graph.independentCycle2A.provider.get())
  
  // Verify lazy cycle resolution
  assertNotNull(graph.lazyCycle1)
  assertNotNull(graph.lazyCycle2)
  assertEquals(graph.lazyCycle1, graph.lazyCycle2.c1)
  assertEquals(graph.lazyCycle2, graph.lazyCycle1.lazy.get())
  
  // Verify mixed Provider and Lazy
  assertNotNull(graph.mixedCycle1)
  assertNotNull(graph.mixedCycle2)
  assertNotNull(graph.mixedCycle3)
  assertEquals(graph.mixedCycle1, graph.mixedCycle2.c1)
  assertEquals(graph.mixedCycle2, graph.mixedCycle3.c2)
  assertEquals(graph.mixedCycle3, graph.mixedCycle1.provider.get())
  assertEquals(graph.mixedCycle3, graph.mixedCycle2.lazy.get())
  
  // Verify consumer has access to all cycles
  assertNotNull(graph.cycleConsumer)
  assertEquals(graph.cycleA, graph.cycleConsumer.cycleA)
  assertEquals(graph.complexCycle1, graph.cycleConsumer.complexCycle1)
  assertEquals(graph.cycleWithExternal1, graph.cycleConsumer.cycleWithExternal1)
  assertEquals(graph.independentCycle1A, graph.cycleConsumer.independentCycle1A)
  assertEquals(graph.independentCycle2A, graph.cycleConsumer.independentCycle2A)
  assertEquals(graph.lazyCycle1, graph.cycleConsumer.lazyCycle1)
  assertEquals(graph.mixedCycle1, graph.cycleConsumer.mixedCycle1)
  
  return "OK"
}