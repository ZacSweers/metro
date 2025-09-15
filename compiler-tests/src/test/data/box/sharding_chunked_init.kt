// COMPILER_OPTIONS: -Xplugin-option=metro:sharding.enabled=true -Xplugin-option=metro:sharding.keysPerShard=30 -Xplugin-option=metro:sharding.breakCycles=true
// TEST_TARGET: jvm

// Massive graph test - creates bindings that will require chunked initialization
// With 30 bindings per shard and >25 statements per method, we should see initializePart methods

// Generate 100 service classes (no dependencies) - these will be distributed across shards
@Inject class Service001
@Inject class Service002
@Inject class Service003
@Inject class Service004
@Inject class Service005
@Inject class Service006
@Inject class Service007
@Inject class Service008
@Inject class Service009
@Inject class Service010
@Inject class Service011
@Inject class Service012
@Inject class Service013
@Inject class Service014
@Inject class Service015
@Inject class Service016
@Inject class Service017
@Inject class Service018
@Inject class Service019
@Inject class Service020
@Inject class Service021
@Inject class Service022
@Inject class Service023
@Inject class Service024
@Inject class Service025
@Inject class Service026
@Inject class Service027
@Inject class Service028
@Inject class Service029
@Inject class Service030
@Inject class Service031
@Inject class Service032
@Inject class Service033
@Inject class Service034
@Inject class Service035
@Inject class Service036
@Inject class Service037
@Inject class Service038
@Inject class Service039
@Inject class Service040
@Inject class Service041
@Inject class Service042
@Inject class Service043
@Inject class Service044
@Inject class Service045
@Inject class Service046
@Inject class Service047
@Inject class Service048
@Inject class Service049
@Inject class Service050
@Inject class Service051
@Inject class Service052
@Inject class Service053
@Inject class Service054
@Inject class Service055
@Inject class Service056
@Inject class Service057
@Inject class Service058
@Inject class Service059
@Inject class Service060
@Inject class Service061
@Inject class Service062
@Inject class Service063
@Inject class Service064
@Inject class Service065
@Inject class Service066
@Inject class Service067
@Inject class Service068
@Inject class Service069
@Inject class Service070
@Inject class Service071
@Inject class Service072
@Inject class Service073
@Inject class Service074
@Inject class Service075
@Inject class Service076
@Inject class Service077
@Inject class Service078
@Inject class Service079
@Inject class Service080
@Inject class Service081
@Inject class Service082
@Inject class Service083
@Inject class Service084
@Inject class Service085
@Inject class Service086
@Inject class Service087
@Inject class Service088
@Inject class Service089
@Inject class Service090
@Inject class Service091
@Inject class Service092
@Inject class Service093
@Inject class Service094
@Inject class Service095
@Inject class Service096
@Inject class Service097
@Inject class Service098
@Inject class Service099
@Inject class Service100

// Generate repository classes with dependencies to create deeper graphs
@Inject class Repository001(val s1: Service001, val s2: Service002)
@Inject class Repository002(val s3: Service003, val s4: Service004)
@Inject class Repository003(val s5: Service005, val s6: Service006)
@Inject class Repository004(val s7: Service007, val s8: Service008)
@Inject class Repository005(val s9: Service009, val s10: Service010)
@Inject class Repository006(val s11: Service011, val s12: Service012)
@Inject class Repository007(val s13: Service013, val s14: Service014)
@Inject class Repository008(val s15: Service015, val s16: Service016)
@Inject class Repository009(val s17: Service017, val s18: Service018)
@Inject class Repository010(val s19: Service019, val s20: Service020)
@Inject class Repository011(val s21: Service021, val s22: Service022)
@Inject class Repository012(val s23: Service023, val s24: Service024)
@Inject class Repository013(val s25: Service025, val s26: Service026)
@Inject class Repository014(val s27: Service027, val s28: Service028)
@Inject class Repository015(val s29: Service029, val s30: Service030)
@Inject class Repository016(val s31: Service031, val s32: Service032)
@Inject class Repository017(val s33: Service033, val s34: Service034)
@Inject class Repository018(val s35: Service035, val s36: Service036)
@Inject class Repository019(val s37: Service037, val s38: Service038)
@Inject class Repository020(val s39: Service039, val s40: Service040)
@Inject class Repository021(val s41: Service041, val s42: Service042)
@Inject class Repository022(val s43: Service043, val s44: Service044)
@Inject class Repository023(val s45: Service045, val s46: Service046)
@Inject class Repository024(val s47: Service047, val s48: Service048)
@Inject class Repository025(val s49: Service049, val s50: Service050)
@Inject class Repository026(val s51: Service051, val s52: Service052)
@Inject class Repository027(val s53: Service053, val s54: Service054)
@Inject class Repository028(val s55: Service055, val s56: Service056)
@Inject class Repository029(val s57: Service057, val s58: Service058)
@Inject class Repository030(val s59: Service059, val s60: Service060)

// Generate use cases with multiple repository dependencies for more complex wiring
@Inject class UseCase001(val r1: Repository001, val r2: Repository002, val r3: Repository003)
@Inject class UseCase002(val r4: Repository004, val r5: Repository005, val r6: Repository006)
@Inject class UseCase003(val r7: Repository007, val r8: Repository008, val r9: Repository009)
@Inject class UseCase004(val r10: Repository010, val r11: Repository011, val r12: Repository012)
@Inject class UseCase005(val r13: Repository013, val r14: Repository014, val r15: Repository015)
@Inject class UseCase006(val r16: Repository016, val r17: Repository017, val r18: Repository018)
@Inject class UseCase007(val r19: Repository019, val r20: Repository020, val r21: Repository021)
@Inject class UseCase008(val r22: Repository022, val r23: Repository023, val r24: Repository024)
@Inject class UseCase009(val r25: Repository025, val r26: Repository026, val r27: Repository027)
@Inject class UseCase010(val r28: Repository028, val r29: Repository029, val r30: Repository030)

// ViewModels that depend on use cases
@Inject class ViewModel001(val u1: UseCase001, val u2: UseCase002)
@Inject class ViewModel002(val u3: UseCase003, val u4: UseCase004)
@Inject class ViewModel003(val u5: UseCase005, val u6: UseCase006)
@Inject class ViewModel004(val u7: UseCase007, val u8: UseCase008)
@Inject class ViewModel005(val u9: UseCase009, val u10: UseCase010)

// Create some cyclic dependencies that should be handled with Provider indirection
@Inject class CyclicA(val b: Provider<CyclicB>, val vm: ViewModel001)
@Inject class CyclicB(val c: Provider<CyclicC>, val vm: ViewModel002)
@Inject class CyclicC(val a: Provider<CyclicA>, val vm: ViewModel003)

// Binding container with many provisions
@BindingContainer
class MassiveBindingContainer {
  @Provides fun provideString1(): String = "str1"
  @Provides fun provideString2(): String = "str2"
  @Provides fun provideString3(): String = "str3"
  @Provides fun provideString4(): String = "str4"
  @Provides fun provideString5(): String = "str5"
  @Provides fun provideInt1(): Int = 1
  @Provides fun provideInt2(): Int = 2
  @Provides fun provideInt3(): Int = 3
  @Provides fun provideInt4(): Int = 4
  @Provides fun provideInt5(): Int = 5
  @Provides fun provideLong1(): Long = 1L
  @Provides fun provideLong2(): Long = 2L
  @Provides fun provideLong3(): Long = 3L
  @Provides fun provideLong4(): Long = 4L
  @Provides fun provideLong5(): Long = 5L
}

// Aggregate service to tie everything together
@Inject class AggregateService(
  val vm1: ViewModel001,
  val vm2: ViewModel002,
  val vm3: ViewModel003,
  val vm4: ViewModel004,
  val vm5: ViewModel005,
  val cyclicA: Provider<CyclicA>,
  val cyclicB: Provider<CyclicB>,
  val cyclicC: Provider<CyclicC>
)

@DependencyGraph(bindingContainers = [MassiveBindingContainer::class])
interface MassiveShardedGraph {
  // Sample of services (not all exposed for brevity)
  val service001: Service001
  val service010: Service010
  val service025: Service025
  val service050: Service050
  val service075: Service075
  val service100: Service100
  
  // Sample of repositories
  val repository001: Repository001
  val repository010: Repository010
  val repository020: Repository020
  val repository030: Repository030
  
  // Sample of use cases
  val useCase001: UseCase001
  val useCase005: UseCase005
  val useCase010: UseCase010
  
  // All view models
  val viewModel001: ViewModel001
  val viewModel002: ViewModel002
  val viewModel003: ViewModel003
  val viewModel004: ViewModel004
  val viewModel005: ViewModel005
  
  // Cyclic dependencies
  val cyclicA: CyclicA
  val cyclicB: CyclicB
  val cyclicC: CyclicC
  
  // Aggregate
  val aggregateService: AggregateService
  
  // Module provisions
  val string1: String
  val string5: String
  val int1: Int
  val int5: Int
  val long1: Long
  val long5: Long
}

fun box(): String {
  val graph = createGraph<MassiveShardedGraph>()
  
  // Verify services are correctly instantiated
  assertNotNull(graph.service001)
  assertNotNull(graph.service010)
  assertNotNull(graph.service025)
  assertNotNull(graph.service050)
  assertNotNull(graph.service075)
  assertNotNull(graph.service100)
  
  // Verify repositories have correct dependencies
  assertNotNull(graph.repository001)
  assertEquals(graph.service001, graph.repository001.s1)
  assertEquals(graph.service002, graph.repository001.s2)
  
  assertNotNull(graph.repository010)
  assertEquals(graph.service019, graph.repository010.s19)
  assertEquals(graph.service020, graph.repository010.s20)
  
  // Verify use cases are wired correctly
  assertNotNull(graph.useCase001)
  assertEquals(graph.repository001, graph.useCase001.r1)
  assertEquals(graph.repository002, graph.useCase001.r2)
  assertEquals(graph.repository003, graph.useCase001.r3)
  
  assertNotNull(graph.useCase005)
  assertEquals(graph.repository013, graph.useCase005.r13)
  assertEquals(graph.repository014, graph.useCase005.r14)
  assertEquals(graph.repository015, graph.useCase005.r15)
  
  // Verify view models
  assertNotNull(graph.viewModel001)
  assertEquals(graph.useCase001, graph.viewModel001.u1)
  assertEquals(graph.useCase002, graph.viewModel001.u2)
  
  assertNotNull(graph.viewModel003)
  assertEquals(graph.useCase005, graph.viewModel003.u5)
  assertEquals(graph.useCase006, graph.viewModel003.u6)
  
  // Verify cyclic dependencies work through Provider indirection
  assertNotNull(graph.cyclicA)
  assertNotNull(graph.cyclicB)
  assertNotNull(graph.cyclicC)
  
  // Verify the cycle: A -> B -> C -> A (via Providers)
  assertEquals(graph.cyclicB, graph.cyclicA.b())
  assertEquals(graph.cyclicC, graph.cyclicB.c())
  assertEquals(graph.cyclicA, graph.cyclicC.a())
  
  // Verify view models in cyclic classes
  assertEquals(graph.viewModel001, graph.cyclicA.vm)
  assertEquals(graph.viewModel002, graph.cyclicB.vm)
  assertEquals(graph.viewModel003, graph.cyclicC.vm)
  
  // Verify aggregate service
  assertNotNull(graph.aggregateService)
  assertEquals(graph.viewModel001, graph.aggregateService.vm1)
  assertEquals(graph.viewModel002, graph.aggregateService.vm2)
  assertEquals(graph.viewModel003, graph.aggregateService.vm3)
  assertEquals(graph.viewModel004, graph.aggregateService.vm4)
  assertEquals(graph.viewModel005, graph.aggregateService.vm5)
  
  // Verify aggregate's cyclic dependencies via Providers
  assertEquals(graph.cyclicA, graph.aggregateService.cyclicA())
  assertEquals(graph.cyclicB, graph.aggregateService.cyclicB())
  assertEquals(graph.cyclicC, graph.aggregateService.cyclicC())
  
  // Verify module provisions
  assertEquals("str1", graph.string1)
  assertEquals("str5", graph.string5)
  assertEquals(1, graph.int1)
  assertEquals(5, graph.int5)
  assertEquals(1L, graph.long1)
  assertEquals(5L, graph.long5)
  
  // This test with 100+ services, 30+ repositories, 10 use cases, 5 view models, 
  // cyclic dependencies, and 15 module provisions (~160+ bindings total)
  // with keysPerShard=30 should generate 5+ shards.
  // Each shard with 30 bindings will likely have >25 initializers requiring chunking.
  
  return "OK"
}