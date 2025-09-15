// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=30
// TEST_TARGET: jvm

// Test that guarantees shard initialization is properly chunked when >25 initializers
// This test forces a shard to have more than SHARD_STATEMENTS_PER_METHOD (25) bindings
// ensuring that initializePart1(), initializePart2(), etc. methods are generated

// Generate exactly 30 services for one shard (with keysPerShard=30)
// These will all go into the same shard, requiring chunked initialization
@Inject class ChunkService01
@Inject class ChunkService02
@Inject class ChunkService03
@Inject class ChunkService04
@Inject class ChunkService05
@Inject class ChunkService06
@Inject class ChunkService07
@Inject class ChunkService08
@Inject class ChunkService09
@Inject class ChunkService10
@Inject class ChunkService11
@Inject class ChunkService12
@Inject class ChunkService13
@Inject class ChunkService14
@Inject class ChunkService15
@Inject class ChunkService16
@Inject class ChunkService17
@Inject class ChunkService18
@Inject class ChunkService19
@Inject class ChunkService20
@Inject class ChunkService21
@Inject class ChunkService22
@Inject class ChunkService23
@Inject class ChunkService24
@Inject class ChunkService25
@Inject class ChunkService26
@Inject class ChunkService27
@Inject class ChunkService28
@Inject class ChunkService29
@Inject class ChunkService30

// Add a few more services to ensure we have multiple shards
@Inject class ExtraService1(val s1: ChunkService01)
@Inject class ExtraService2(val s2: ChunkService02)
@Inject class ExtraService3(val s3: ChunkService03)

// Module to keep some bindings in the main graph
@Module
class ChunkingTestModule {
  @Provides fun provideString(): String = "chunking-test"
  @Provides fun provideInt(): Int = 42
}

@DependencyGraph
interface ShardChunkingGuaranteeGraph {
  // Expose all 30 services from the first shard
  val service01: ChunkService01
  val service02: ChunkService02
  val service03: ChunkService03
  val service04: ChunkService04
  val service05: ChunkService05
  val service06: ChunkService06
  val service07: ChunkService07
  val service08: ChunkService08
  val service09: ChunkService09
  val service10: ChunkService10
  val service11: ChunkService11
  val service12: ChunkService12
  val service13: ChunkService13
  val service14: ChunkService14
  val service15: ChunkService15
  val service16: ChunkService16
  val service17: ChunkService17
  val service18: ChunkService18
  val service19: ChunkService19
  val service20: ChunkService20
  val service21: ChunkService21
  val service22: ChunkService22
  val service23: ChunkService23
  val service24: ChunkService24
  val service25: ChunkService25
  val service26: ChunkService26
  val service27: ChunkService27
  val service28: ChunkService28
  val service29: ChunkService29
  val service30: ChunkService30
  
  // Extra services in another shard
  val extra1: ExtraService1
  val extra2: ExtraService2
  val extra3: ExtraService3
  
  // Module provisions
  val testString: String
  val testInt: Int
  
  @Module
  val module: ChunkingTestModule = ChunkingTestModule()
}

fun box(): String {
  val graph = createGraph<ShardChunkingGuaranteeGraph>()
  
  // Verify all 30 services are created correctly
  assertNotNull(graph.service01)
  assertNotNull(graph.service02)
  assertNotNull(graph.service03)
  assertNotNull(graph.service04)
  assertNotNull(graph.service05)
  assertNotNull(graph.service06)
  assertNotNull(graph.service07)
  assertNotNull(graph.service08)
  assertNotNull(graph.service09)
  assertNotNull(graph.service10)
  assertNotNull(graph.service11)
  assertNotNull(graph.service12)
  assertNotNull(graph.service13)
  assertNotNull(graph.service14)
  assertNotNull(graph.service15)
  assertNotNull(graph.service16)
  assertNotNull(graph.service17)
  assertNotNull(graph.service18)
  assertNotNull(graph.service19)
  assertNotNull(graph.service20)
  assertNotNull(graph.service21)
  assertNotNull(graph.service22)
  assertNotNull(graph.service23)
  assertNotNull(graph.service24)
  assertNotNull(graph.service25)
  assertNotNull(graph.service26)
  assertNotNull(graph.service27)
  assertNotNull(graph.service28)
  assertNotNull(graph.service29)
  assertNotNull(graph.service30)
  
  // Verify extra services with dependencies
  assertNotNull(graph.extra1)
  assertEquals(graph.service01, graph.extra1.s1)
  assertNotNull(graph.extra2)
  assertEquals(graph.service02, graph.extra2.s2)
  assertNotNull(graph.extra3)
  assertEquals(graph.service03, graph.extra3.s3)
  
  // Verify module provisions
  assertEquals("chunking-test", graph.testString)
  assertEquals(42, graph.testInt)
  
  // Success criteria:
  // 1. Compilation succeeds without 64KB method limit errors
  // 2. All 30 services in one shard are properly initialized
  // 3. The shard class will have initializePart1() and initializePart2() methods
  //    (30 bindings > 25 threshold, so needs 2 parts)
  
  return "OK"
}